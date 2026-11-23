/*
 * Servizio applicativo dell'autenticazione: registrazione, login con password o con Google, refresh con
 * rotazione dei token, logout e cambio password. Qui stanno le regole di sicurezza della sessione (limiti dei
 * tentativi, ordine dei lock, revoca delle sessioni); i controller si limitano a chiamarlo.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.RefreshToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserIdentityRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.shared.domain.exception.AgeRequirementNotMetException;
import it.unical.geosentinel.shared.domain.exception.BirthDateRequiredException;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.domain.exception.InvalidMfaCodeException;
import it.unical.geosentinel.identity.domain.IdentityProvider;
import it.unical.geosentinel.identity.domain.UserIdentity;
import it.unical.geosentinel.identity.application.port.GoogleIdentity;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.shared.domain.exception.AccountManagedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestisce il ciclo di vita delle sessioni. Una sessione è formata da un access token JWT di breve durata e da
 * una famiglia di refresh token ({@link RefreshToken}) che ruotano a ogni refresh. Lo usano
 * {@code AuthController} e {@code UserController}.
 */
@Service
public class AuthService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * Namespace dell'advisory lock di PostgreSQL a due chiavi preso sull'email durante il login con Google.
     * Deve essere diverso da quelli usati da altri moduli (il modulo media usa 7001).
     */
    private static final int GOOGLE_EMAIL_LOCK_NAMESPACE = 7002;
    private final UserIdentityRepository userIdentityRepository;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final MfaService mfaService;
    private final DeviceService deviceService;
    private final JwtIssuer jwtIssuer;
    private final Clock clock;
    /** Durata di un singolo refresh token. */
    private final Duration refreshTtl;
    /** Scadenza assoluta della sessione dal login: le rotazioni non la allungano. */
    private final Duration sessionMaxTtl;
    /**
     * Per quanto tempo un token appena ruotato resta accettato, purché il suo successore non sia mai stato
     * usato. Copre il client che ha perso la risposta del refresh.
     */
    private final Duration refreshReuseGrace;
    /** Età minima per registrarsi, in anni. */
    private final int minAge;
    /**
     * Hash di una password casuale. Il login esegue sempre un confronto BCrypt, anche se l'email non esiste,
     * così il tempo di risposta non rivela quali email sono registrate.
     */
    private final String dummyPasswordHash;

    /**
     * Emettitore degli access token. È un'interfaccia del livello application, implementata da
     * {@code JwtTokenService} nell'infrastruttura, così questo service non dipende dalla libreria JWT.
     */
    public interface JwtIssuer { IssuedAccessToken issueAccessToken(User user); }

    /**
     * Le durate arrivano con {@code @Value} e non da {@code JwtProperties} perché il livello application non
     * deve dipendere da quello infrastructure. L'età minima segue l'art. 8 del GDPR: 14 anni in Italia, da 13
     * a 16 negli altri Stati dell'Unione.
     */
    public AuthService(
            UserIdentityRepository userIdentityRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenBlacklist tokenBlacklist,
            LoginAttemptLimiter loginAttemptLimiter,
            MfaService mfaService,
            DeviceService deviceService,
            JwtIssuer jwtIssuer,
            Clock clock,
            @Value("${geosentinel.jwt.refresh-ttl}") Duration refreshTtl,
            @Value("${geosentinel.jwt.session-max-ttl}") Duration sessionMaxTtl,
            @Value("${geosentinel.jwt.refresh-reuse-grace}") Duration refreshReuseGrace,
            @Value("${geosentinel.registration.min-age}") int minAge
    ) {
        this.userIdentityRepository = userIdentityRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklist = tokenBlacklist;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.mfaService = mfaService;
        this.deviceService = deviceService;
        this.jwtIssuer = jwtIssuer;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
        this.sessionMaxTtl = sessionMaxTtl;
        this.refreshReuseGrace = refreshReuseGrace;
        this.minAge = minAge;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Registra un nuovo escursionista e apre la sua prima sessione. L'età si controlla prima di qualsiasi
     * lettura o scrittura: di chi è sotto la soglia non resta nessun dato, e un minore non scopre nemmeno se
     * un'email è registrata. Un'email già registrata produce un 422, che rivela l'esistenza dell'account: è una
     * scelta accettata, mitigata dal rate limit sugli endpoint di autenticazione.
     *
     * @param cmd dati della registrazione
     * @return i token della nuova sessione
     * @throws AgeRequirementNotMetException se l'utente non ha l'età minima
     * @throws BusinessRuleViolationException se l'email è già registrata
     */
    @Transactional
    public AuthTokens register(RegisterCommand cmd) {
        requireMinAge(cmd.birthDate());

        String email = User.normalizeEmail(cmd.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessRuleViolationException("Email already registered");
        }

        String encodedPassword = passwordEncoder.encode(cmd.rawPassword());
        User user = User.createHiker(email, encodedPassword, cmd.displayName());
        user.confirmAge(clock.instant());
        return createAuthSession(userRepository.save(user), null);
    }

    /**
     * Cambia la password di un utente autenticato. Revoca tutte le sessioni (comprese quelle di un eventuale
     * ladro) e ne apre una nuova per il dispositivo che ha fatto il cambio. Una password attuale sbagliata
     * produce un 422 e non un 401, perché un 401 farebbe credere all'app che il token sia scaduto e la
     * spingerebbe a fare refresh o logout.
     *
     * @param userId utente autenticato
     * @param currentPassword password attuale
     * @param newPassword nuova password
     * @param mfaCode codice del secondo fattore, richiesto solo se l'utente lo ha attivo
     * @return i token della nuova sessione
     * @throws ResourceNotFoundException se l'utente non esiste o è disabilitato
     * @throws BusinessRuleViolationException se l'account non ha password o quella attuale è sbagliata
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional
    public AuthTokens changePassword(UUID userId, String currentPassword, String newPassword, String mfaCode) {
        // Lock sull'utente: un refresh concorrente aspetta e poi trova la famiglia già revocata
        User user = userRepository.findByIdForUpdate(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if(!user.hasPassword()){
            throw new BusinessRuleViolationException("Account has no password: use the password reset");
        }
        // Stesso limite per account del login: chi ha rubato un access token (che dura 15 minuti)
        // non può usarlo per indovinare la password
        loginAttemptLimiter.tryAcquire(sha256Hex(user.getEmail())).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleViolationException("Current password is incorrect");
        }
        // Il cambio apre una sessione nuova, quindi chiede il secondo fattore come il login. Altrimenti un access
        // token rubato più la password diventerebbero una sessione lunga che chiude fuori il titolare
        mfaService.verifyStepUp(userId, mfaCode);
        // Password e secondo fattore giusti: il titolare non deve consumare i tentativi riservati agli errori
        loginAttemptLimiter.reset(sha256Hex(user.getEmail()));

        Instant now = clock.instant();
        user.updatePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllOfUser(userId, now);
        // Si revocano anche gli access token ancora validi degli altri dispositivi, fino al secondo precedente:
        // il claim iat ha la precisione del secondo e la sessione nuova, nata in questo secondo, deve restare valida
        tokenBlacklist.revokeAllIssuedUntil(userId, now.minusSeconds(1));
        return createAuthSession(user, null);
    }

    /**
     * Login con email e password, seguito dal secondo fattore se attivo.
     * <p>
     * Il limite per account (chiave = hash dell'email, mai l'email in chiaro su Redis) ferma il credential
     * stuffing, cioè molti IP che provano la stessa email, che il limite per IP non vede. Si controlla per
     * primo: il comportamento è identico per account esistenti e non (niente enumerazione) e non si paga il
     * costo di BCrypt. Il tentativo si consuma prima di sapere se la password è giusta; dopo un login riuscito
     * (password e secondo fattore) il limite si azzera, così chi entra spesso non viene bloccato. Il limite è a due
     * livelli (account e IP, più uno largo per account, vedi {@code RedisLoginAttemptLimiter}): chi sbaglia da un
     * solo IP blocca soltanto se stesso. Con {@code noRollbackFor} un codice MFA sbagliato lascia committati il contatore e l'evento d'avviso.
     *
     * @param email email inserita
     * @param rawPassword password inserita
     * @param mfaCode codice del secondo fattore, può essere {@code null}
     * @return i token della nuova sessione
     * @throws BadCredentialsException se le credenziali sono sbagliate o l'account è disabilitato o senza password
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public AuthTokens login(String email, String rawPassword, String mfaCode) {
        String normalizedEmail = User.normalizeEmail(email);
        loginAttemptLimiter.tryAcquire(sha256Hex(normalizedEmail)).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });

        Optional<User> user = userRepository.findByEmail(normalizedEmail);
        // Un account senza password (nato da Google) ha l'hash null: si confronta comunque con quello fittizio
        String storedHash = user.map(User::getPasswordHash).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, storedHash != null ? storedHash : dummyPasswordHash);
        if (user.isEmpty() || storedHash == null || !passwordMatches || !user.get().isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        // Il secondo fattore si chiede solo dopo la password: la risposta mfa-required la vede solo chi la conosce
        mfaService.verifyLogin(user.get(), mfaCode);
        // Solo qui, dopo anche il secondo fattore: un codice MFA sbagliato non deve restituire i tentativi
        loginAttemptLimiter.reset(sha256Hex(normalizedEmail));
        return createAuthSession(user.get(), null);
    }

    /**
     * Ruota un refresh token: revoca quello presentato e ne emette uno nuovo nella stessa famiglia, insieme a un
     * nuovo access token. Se il token era già stato usato (fuori dal periodo di grazia) si sospetta un furto e
     * si revoca l'intera famiglia. Con {@code noRollbackFor} quella revoca resta anche se poi si risponde 401.
     *
     * @param rawRefreshToken refresh token in chiaro inviato dal client
     * @return i token ruotati
     * @throws BadCredentialsException se il token è sconosciuto, già usato, scaduto o l'utente è disabilitato
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String hash = sha256Hex(rawRefreshToken);

        // Prima il lock sull'utente, poi il token: è lo stesso ordine di changePassword e della cancellazione
        // dell'account, così non ci sono deadlock. Un cambio password in corso finisce prima e la revoca dei
        // suoi token è visibile all'UPDATE qui sotto
        UUID userId = refreshTokenRepository.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        userRepository.findByIdForUpdate(userId);

        // UPDATE atomico: tra richieste concorrenti con lo stesso token solo una vince la rotazione
        int updatedRows = refreshTokenRepository.revokeIfActive(hash, now);
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (updatedRows == 0 && !acceptedInRotationGrace(oldToken, now)) {
            // Token già usato: possibile furto, si revoca l'intera famiglia
            refreshTokenRepository.revokeFamily(oldToken.getFamilyId(), now);
            throw new BadCredentialsException("Invalid refresh token");
        }

        // Copre sia la scadenza del token sia quella assoluta della sessione
        if (oldToken.isExpired(now)) {
            throw new BadCredentialsException("Refresh token expired");
        }

        User user = oldToken.getUser();
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        return createAuthSession(user, oldToken);
    }

    /**
     * Chiude la sessione: revoca la famiglia del refresh token e mette l'access token nella blacklist fino alla
     * sua scadenza. Con {@code noRollbackFor}: se Redis non risponde la blacklist lancia
     * {@link IllegalStateException}, ma la revoca della famiglia nel database resta (il refresh token è comunque
     * inutilizzabile) e l'access token scade da solo entro la sua durata.
     *
     * @param accessToken access token della richiesta, può essere {@code null}
     * @param rawRefreshToken refresh token in chiaro, può essere {@code null} o vuoto
     */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public void logout(Jwt accessToken, String rawRefreshToken) {
        Instant now = clock.instant();

        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String hash = sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(hash)
                    // Si revoca solo una sessione dell'utente autenticato: il token di un altro utente si ignora
                    .filter(token -> accessToken != null
                            && token.getUser().getId().toString().equals(accessToken.getSubject()))
                    .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));
        }

        if (accessToken != null) {
            String jti = accessToken.getId();
            Instant expiresAt = accessToken.getExpiresAt();
            if (jti != null && expiresAt != null && expiresAt.isAfter(now)) {
                tokenBlacklist.revoke(jti, Duration.between(now, expiresAt));
            }
        }
    }

    /**
     * Decide se accettare un token già ruotato perché il client non ha ricevuto la risposta della rotazione
     * (per esempio per la rete caduta). Si accetta solo se il token è stato revocato da una rotazione da non più
     * di {@link #refreshReuseGrace} e il suo successore non è mai stato usato: il successore viene revocato con
     * un UPDATE atomico e si ruota di nuovo. Un ladro che usa il token vecchio dentro la finestra prende il
     * posto del successore, ma appena il client legittimo usa il proprio la famiglia viene revocata.
     *
     * @param token token già revocato presentato dal client
     * @param now istante attuale
     * @return {@code true} se il riuso è accettato
     */
    private boolean acceptedInRotationGrace(RefreshToken token, Instant now) {
        return token.isInRotationGrace(now, refreshReuseGrace)
                && refreshTokenRepository.revokeIfActiveById(token.getReplacedById(), now) == 1;
    }

    /**
     * Emette un access token e un refresh token. Con {@code previous == null} apre una sessione nuova (famiglia
     * nuova); altrimenti è una rotazione e mantiene la famiglia e la scadenza assoluta del token precedente.
     *
     * @param user utente della sessione
     * @param previous token ruotato, oppure {@code null} per una sessione nuova
     * @return i token da restituire al client
     */
    private AuthTokens createAuthSession(User user, RefreshToken previous) {
        Instant now = clock.instant();
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(user);

        UUID familyId = previous == null ? UUID.randomUUID() : previous.getFamilyId();
        Instant familyExpiresAt = previous == null ? now.plus(sessionMaxTtl) : previous.getFamilyExpiresAt();
        // Il refresh token non può sopravvivere alla sessione
        Instant refreshExpiresAt = min(now.plus(refreshTtl), familyExpiresAt);

        String rawRefreshToken = generateSecureRandomToken();
        RefreshToken saved = refreshTokenRepository.save(new RefreshToken(
                user, sha256Hex(rawRefreshToken), familyId, refreshExpiresAt, familyExpiresAt, now));
        if (previous != null) {
            previous.replacedBy(saved.getId());
        }

        return AuthTokens.bearer(accessToken.tokenValue(), accessToken.expiresAt(), rawRefreshToken);
    }

    /**
     * Login con un'identità Google già verificata. Tre casi: identità già collegata (si entra), email già usata
     * da un account (si collega Google a quell'account), oppure account nuovo (si crea con le regole della
     * registrazione). L'ordine dei lock è lo stesso di changePassword: prima l'utente, poi i suoi token.
     * Con {@code noRollbackFor}, come nel login, l'email d'avviso (inviata dopo il commit) parte anche quando il
     * codice MFA è sbagliato; è sicuro perché la verifica MFA avviene prima di ogni scrittura.
     *
     * @param google identità restituita dalla verifica del token di Google
     * @param birthDate data di nascita, obbligatoria solo per creare un account nuovo
     * @param mfaCode codice del secondo fattore, può essere {@code null}
     * @return i token della sessione e se l'account è stato appena creato
     * @throws BusinessRuleViolationException se l'email Google non è verificata o è già collegata a un altro account Google
     * @throws AccountManagedException se l'email appartiene a un account dello staff
     * @throws BadCredentialsException se l'account è disabilitato
     * @throws BirthDateRequiredException se serve creare l'account e manca la data di nascita
     * @throws AgeRequirementNotMetException se l'utente non ha l'età minima
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public GoogleSignIn loginWithGoogle(GoogleIdentity google, LocalDate birthDate, String mfaCode) {
        // Limite per account sul subject Google, come quello sull'email nel login con password:
        // è lo stesso credential stuffing, su un altro canale
        String googleKey = sha256Hex("google:" + google.subject());
        loginAttemptLimiter.tryAcquire(googleKey).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });

        // Un'email non verificata non apre né crea nulla. Altrimenti un account Google con l'email (non
        // verificata) di un'altra persona creerebbe un account con quell'email e, quando la vittima entra con il
        // proprio Google, l'identità dell'attaccante resterebbe collegata all'account della vittima
        if (!google.emailVerified()) {
            throw new BusinessRuleViolationException("Google account email is not verified");
        }
        Instant now = clock.instant();
        String email = User.normalizeEmail(google.email());
        // Serializza i login Google concorrenti con la stessa email, che altrimenti potrebbero creare due account
        userIdentityRepository.lockEmail(GOOGLE_EMAIL_LOCK_NAMESPACE, email);

        // Caso 1: identità già collegata, si entra normalmente
        Optional<UserIdentity> linked = userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject());
        if (linked.isPresent()) {
            User user = lockEnabled(linked.get().getUser().getId());
            mfaService.verifyLogin(user, mfaCode);
            loginAttemptLimiter.reset(googleKey);
            return new GoogleSignIn(createAuthSession(user, null), false);
        }

        // Caso 2: email già usata da un account, si collega Google (la registrazione non verifica l'email, Google sì)
        Optional<User> existing = userRepository.findByEmail(email)
                .flatMap(found -> userRepository.findByIdForUpdate(found.getId()));
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getRole() != Role.HIKER) {
                throw new AccountManagedException("Staff accounts cannot be linked to Google");
            }
            if (!user.isEnabled()) {
                throw new BadCredentialsException("Invalid credentials");
            }

            // Prima di collegare: chi controlla l'account Google della vittima non salta il secondo fattore
            mfaService.verifyLogin(user, mfaCode);

            // Stessa email ma subject diverso: l'account è già collegato a un altro Google, non si aggiunge
            // una seconda identità
            if (userIdentityRepository.existsByUser_IdAndProvider(user.getId(), IdentityProvider.GOOGLE)) {
                throw new BusinessRuleViolationException("Email already linked to another Google account");
            }
            // Password azzerata e sessioni chiuse: chi aveva registrato l'email non entra più con la sua password
            user.clearPassword();
            userIdentityRepository.save(new UserIdentity(user, IdentityProvider.GOOGLE, google.subject(), email, now));
            refreshTokenRepository.revokeAllOfUser(user.getId(), now);
            // Anche i telefoni di chi aveva registrato l'email smettono di ricevere le notifiche push dell'account
            deviceService.deleteAllOf(user.getId());
            tokenBlacklist.revokeAllIssuedUntil(user.getId(), now.minusSeconds(1));
            loginAttemptLimiter.reset(googleKey);
            return new GoogleSignIn(createAuthSession(user, null), false);
        }

        // Caso 3: account nuovo, con le stesse regole della registrazione (età compresa)
        if (birthDate == null) {
            throw new BirthDateRequiredException("Birth date is required to create an account");
        }
        requireMinAge(birthDate);
        User user = User.createHikerWithoutPassword(email, displayNameOf(google));
        user.confirmAge(now);
        User saved = userRepository.save(user);
        userIdentityRepository.save(new UserIdentity(saved, IdentityProvider.GOOGLE, google.subject(), email, now));
        loginAttemptLimiter.reset(googleKey);
        return new GoogleSignIn(createAuthSession(saved, null), true);
    }

    /**
     * Legge l'utente con un lock in scrittura e controlla che sia abilitato.
     *
     * @param userId utente
     * @return l'utente bloccato
     * @throws BadCredentialsException se l'utente non esiste o è disabilitato
     */
    private User lockEnabled(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }

    /**
     * Controlla l'età minima. Va chiamato prima di ogni lettura o scrittura, così di chi è sotto la soglia
     * non resta nessun dato.
     *
     * @param birthDate data di nascita dichiarata
     * @throws AgeRequirementNotMetException se l'utente è più giovane di {@link #minAge}
     */
    private void requireMinAge(LocalDate birthDate) {
        if (Period.between(birthDate, LocalDate.now(clock)).getYears() < minAge) {
            throw new AgeRequirementNotMetException("Registration is not available for this age");
        }
    }

    /**
     * Ricava il nome del profilo da quello di Google, che può mancare o non rispettare i limiti del profilo
     * (da 2 a 100 caratteri): se è troppo corto si usa un nome generico, se è troppo lungo si tronca.
     *
     * @param google identità Google
     * @return un nome valido per il profilo
     */
    private static String displayNameOf(GoogleIdentity google) {
        String name = google.displayName() == null ? "" : google.displayName().strip();
        if (name.length() < 2) {
            return "Escursionista";
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    /** @return il più vicino nel tempo tra i due istanti */
    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Genera un refresh token: 32 byte casuali (256 bit) in Base64 URL-safe senza padding.
     *
     * @return il token in chiaro da consegnare al client
     */
    private static String generateSecureRandomToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Hash SHA-256 usato per salvare i refresh token e per le chiavi dei limiti su Redis, così i valori
     * in chiaro non finiscono mai nel database o in Redis. Per un token di 256 bit casuali non serve un hash lento.
     *
     * @param input testo da hashare
     * @return l'hash in esadecimale minuscolo
     */
    private static String sha256Hex(String input) {
        Objects.requireNonNull(input, "input cannot be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in JVM", e);
        }
    }
}
