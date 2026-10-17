package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
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

// Reset della password con codice via email. Il codice si salva solo come hash, vale una volta e dopo
// troppi errori si consuma. Le risposte non dicono se l'email esiste.
@Service
public class PasswordResetService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // Confrontato al posto di un codice malformato: stessa lunghezza di un codice vero, ma non coincide mai
    // (il risultato del confronto si scarta perché wellFormed è false)
    private static final String MALFORMED_PLACEHOLDER = "0".repeat(16);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final PasswordResetLimiter limiter;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration ttl;
    private final int maxAttempts;
    // Hash fittizio: un confronto vero anche quando il codice non esiste, così il tempo non dice se l'email c'è
    private final String dummyCodeHash;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository resetTokens,
            RefreshTokenRepository refreshTokenRepository,
            TokenBlacklist tokenBlacklist,
            PasswordResetLimiter limiter,
            LoginAttemptLimiter loginAttemptLimiter,
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
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
        this.dummyCodeHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // Nel thread della richiesta solo il limite (vale anche per le email sconosciute) e l'evento: ricerca dell'utente,
    // BCrypt e scrittura li fa PasswordResetMailListener in background, così il tempo di risposta è lo stesso
    // che l'account esista o no
    public void requestReset(String email) {
        String normalizedEmail = User.normalizeEmail(email);
        limiter.tryAcquire(sha256Hex(normalizedEmail)).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        events.publishEvent(new PasswordResetRequested(normalizedEmail));
    }

    // Chiamato dal listener. Il codice in chiaro esce solo dal valore di ritorno, dopo il commit (lo fa il proxy al
    // ritorno): l'email non parte mai per un codice che non è stato salvato
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

    // Stesso ordine dei lock di changePassword: prima l'utente, poi i suoi token. noRollbackFor: il codice sbagliato
    // deve restare contato, anche se poi si risponde 422
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public void confirmReset(String email, String rawCode, String newPassword) {
        Instant now = clock.instant();
        String code = PasswordResetCode.normalize(rawCode);
        boolean wellFormed = PasswordResetCode.isWellFormed(code);
        // BCrypt sempre, anche su un codice malformato: saltarlo renderebbe veloce solo il caso "codice aperto",
        // e il tempo direbbe che l'email esiste. Mai l'input grezzo: oltre 72 byte BCrypt lancia un'eccezione (500)
        String candidate = wellFormed ? code : MALFORMED_PLACEHOLDER;
        Optional<User> user = findActiveForUpdate(User.normalizeEmail(email));

        PasswordResetToken token = user
                .flatMap(owner -> resetTokens.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(owner.getId()))
                .filter(open -> open.isUsable(now))
                .orElse(null);
        if (token == null) {
            // Nessun codice aperto: un confronto vero comunque, poi l'errore
            passwordEncoder.matches(candidate, dummyCodeHash);
            throw new InvalidResetCodeException();
        }
        boolean matches = passwordEncoder.matches(candidate, token.getCodeHash());
        if (!wellFormed || !matches) {
            token.registerFailure(now, maxAttempts);
            throw new InvalidResetCodeException();
        }

        // Codice giusto: vale una volta. Lo si consuma in memoria prima dell'UPDATE in blocco, così non c'è disallineamento
        token.consume(now);
        resetTokens.consumeAllOpenOf(user.get().getId(), now);

        user.get().updatePassword(passwordEncoder.encode(newPassword));
        // Chi ha il codice ha provato di possedere l'email: tutte le sessioni si chiudono, e non se ne apre una nuova
        refreshTokenRepository.revokeAllOfUser(user.get().getId(), now);
        tokenBlacklist.revokeAllIssuedUntil(user.get().getId(), now);
        // Chi è stato bloccato dai login sbagliati deve poter entrare subito con la password nuova
        loginAttemptLimiter.reset(sha256Hex(user.get().getEmail()));
    }

    // Codice appena emesso, da spedire. toString senza dati: non finisce nei log per sbaglio
    public record IssuedResetCode(String email, String code, Duration validFor) {
        @Override
        public String toString() {
            return "IssuedResetCode[email=***, code=***]";
        }
    }

    // Lock sull'utente attivo. Vuoto per email sconosciuta, disabilitata o cancellata
    private Optional<User> findActiveForUpdate(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail)
                .flatMap(found -> userRepository.findByIdForUpdate(found.getId()))
                .filter(User::isEnabled);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in JVM", e);
        }
    }
}