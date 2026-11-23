/*
 * Servizio applicativo del reset della password con un codice inviato via email. È diviso in tre passi:
 * richiesta (nel thread HTTP), emissione del codice (in background) e conferma con la nuova password.
 * Le risposte e i tempi non rivelano se un'email è registrata.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.MfaAttemptLimiter;
import it.unical.geosentinel.identity.application.port.PasswordResetLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.PasswordResetCode;
import it.unical.geosentinel.identity.domain.PasswordResetToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.PasswordResetTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.InvalidResetCodeException;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Reset della password con codice via email. Il codice si salva solo come hash BCrypt, vale una volta sola
 * e si consuma dopo troppi tentativi sbagliati. Lo usano {@code PasswordResetController} e
 * {@link PasswordResetMailListener}.
 */
@Service
public class PasswordResetService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * Valore confrontato al posto di un codice malformato: ha la stessa lunghezza di un codice vero, così il
     * confronto BCrypt costa uguale. Il risultato del confronto viene comunque scartato.
     */
    private static final String MALFORMED_PLACEHOLDER = "0".repeat(16);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final PasswordResetLimiter limiter;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final MfaAttemptLimiter mfaAttemptLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /** Validità di un codice dalla sua emissione. */
    private final Duration ttl;
    /** Tentativi sbagliati dopo cui il codice si consuma. */
    private final int maxAttempts;
    /**
     * Hash di un valore casuale: si confronta con questo quando non c'è un codice aperto, così il tempo di
     * risposta non rivela se l'email esiste.
     */
    private final String dummyCodeHash;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository resetTokens,
            RefreshTokenRepository refreshTokenRepository,
            TokenBlacklist tokenBlacklist,
            PasswordResetLimiter limiter,
            LoginAttemptLimiter loginAttemptLimiter,
            MfaAttemptLimiter mfaAttemptLimiter,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${geosentinel.password-reset.ttl}") Duration ttl,
            @Value("${geosentinel.password-reset.max-attempts}") int maxAttempts
    ) {
        this.userRepository = userRepository;
        this.resetTokens = resetTokens;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenBlacklist = tokenBlacklist;
        this.limiter = limiter;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.mfaAttemptLimiter = mfaAttemptLimiter;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
        this.dummyCodeHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Accetta una richiesta di reset. Nel thread della richiesta si fanno solo il controllo del limite (che vale
     * anche per le email sconosciute) e la pubblicazione dell'evento; la ricerca dell'utente, BCrypt e la
     * scrittura li fa {@link PasswordResetMailListener} in background. Così il tempo di risposta è lo stesso
     * che l'account esista o no.
     *
     * @param email email indicata dall'utente
     * @throws RateLimitExceededException se le richieste per quell'email sono troppe
     */
    public void requestReset(String email) {
        String normalizedEmail = User.normalizeEmail(email);
        limiter.tryAcquire(sha256Hex(normalizedEmail)).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        events.publishEvent(new PasswordResetRequested(normalizedEmail));
    }

    /**
     * Genera e salva un nuovo codice, invalidando quelli precedenti. Il codice in chiaro esce solo come valore
     * di ritorno, quindi dopo il commit fatto dal proxy transazionale: l'email non parte mai per un codice che
     * non è stato salvato.
     *
     * @param normalizedEmail email normalizzata
     * @return il codice da spedire, oppure vuoto se l'email non corrisponde a un account attivo
     */
    @Transactional
    public Optional<IssuedResetCode> issueCode(String normalizedEmail) {
        Instant now = clock.instant();
        Optional<User> user = findActiveForUpdate(normalizedEmail);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        String code = PasswordResetCode.generate(SECURE_RANDOM);
        // Un codice nuovo rende invalidi i precedenti
        resetTokens.consumeAllOpenOf(user.get().getId(), now);
        resetTokens.save(new PasswordResetToken(user.get(), passwordEncoder.encode(code), now.plus(ttl), now));
        return Optional.of(new IssuedResetCode(user.get().getEmail(), code, ttl));
    }

    /**
     * Imposta la nuova password se il codice è giusto, poi chiude tutte le sessioni dell'utente senza aprirne
     * una nuova. L'ordine dei lock è quello di {@link AuthService#changePassword}: prima l'utente, poi i token.
     * Con {@code noRollbackFor} un codice sbagliato resta contato anche se si risponde con un 422.
     *
     * @param email email dell'account
     * @param rawCode codice come l'ha scritto l'utente
     * @param newPassword nuova password in chiaro
     * @throws InvalidResetCodeException se non c'è un codice valido o quello inserito è sbagliato
     */
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public void confirmReset(String email, String rawCode, String newPassword) {
        Instant now = clock.instant();
        String code = PasswordResetCode.normalize(rawCode);
        boolean wellFormed = PasswordResetCode.isWellFormed(code);
        // BCrypt si esegue sempre, anche su un codice malformato: saltarlo renderebbe lento solo il caso con un
        // codice aperto, e il tempo rivelerebbe che l'email esiste. Non si passa mai l'input grezzo, perché oltre
        // 72 byte BCrypt lancia un'eccezione e si risponderebbe con un 500
        String candidate = wellFormed ? code : MALFORMED_PLACEHOLDER;
        Optional<User> user = findActiveForUpdate(User.normalizeEmail(email));

        PasswordResetToken token = user
                .flatMap(owner -> resetTokens.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(owner.getId()))
                .filter(open -> open.isUsable(now))
                .orElse(null);
        if (token == null) {
            // Nessun codice aperto: si fa comunque un confronto vero, poi si risponde con l'errore
            passwordEncoder.matches(candidate, dummyCodeHash);
            throw new InvalidResetCodeException();
        }
        boolean matches = passwordEncoder.matches(candidate, token.getCodeHash());
        if (!wellFormed || !matches) {
            token.registerFailure(now, maxAttempts);
            throw new InvalidResetCodeException();
        }

        // Codice giusto: vale una volta sola. Si consuma prima in memoria e poi con l'UPDATE in blocco,
        // così l'entità caricata e il database non sono disallineati
        token.consume(now);
        resetTokens.consumeAllOpenOf(user.get().getId(), now);

        user.get().updatePassword(passwordEncoder.encode(newPassword));
        // Chi ha il codice ha dimostrato di possedere l'email: si chiudono tutte le sessioni e non se ne apre una
        refreshTokenRepository.revokeAllOfUser(user.get().getId(), now);
        tokenBlacklist.revokeAllIssuedUntil(user.get().getId(), now);
        // Chi era stato bloccato dai login sbagliati deve poter entrare subito con la password nuova
        loginAttemptLimiter.reset(sha256Hex(user.get().getEmail()));
        // Si azzerano anche i tentativi della verifica in due passaggi: chi li esauriva usando la vecchia
        // password non la conosce più
        mfaAttemptLimiter.reset(user.get().getId());
    }

    /**
     * Codice appena emesso, da spedire via email.
     *
     * @param email destinatario
     * @param code codice in chiaro, nella forma normalizzata
     * @param validFor validità del codice
     */
    public record IssuedResetCode(String email, String code, Duration validFor) {
        /** Nasconde email e codice, così non finiscono nei log per sbaglio. */
        @Override
        public String toString() {
            return "IssuedResetCode[email=***, code=***]";
        }
    }

    /**
     * Cerca un utente attivo per email e prende il lock in scrittura sulla sua riga.
     *
     * @param normalizedEmail email normalizzata
     * @return l'utente bloccato, oppure vuoto se l'email è sconosciuta o l'account è disabilitato o cancellato
     */
    private Optional<User> findActiveForUpdate(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail)
                .flatMap(found -> userRepository.findByIdForUpdate(found.getId()))
                .filter(User::isEnabled);
    }

    /**
     * @param input testo da hashare (qui l'email, usata come chiave dei limiti su Redis)
     * @return l'hash SHA-256 in esadecimale minuscolo
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in JVM", e);
        }
    }
}
