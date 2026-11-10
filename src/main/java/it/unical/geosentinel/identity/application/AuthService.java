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

@Service
public class AuthService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // Namespace dell'advisory lock a due chiavi sull'email del login Google (MediaService usa 7001)
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
    private final Duration refreshTtl;
    // scadenza assoluta della sessione: le rotazioni non la estendono
    private final Duration sessionMaxTtl;
    // ADR-0019: per quanto un token appena ruotato resta accettato, se il suo successore non è mai stato usato
    private final Duration refreshReuseGrace;
    private final int minAge;
    // Hash fittizio: il login esegue sempre un confronto BCrypt, così il tempo di risposta non rivela se l'email esiste
    private final String dummyPasswordHash;

    public interface JwtIssuer { IssuedAccessToken issueAccessToken(User user); }

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
            // @Value e non JwtProperties: application non dipende da infrastructure
            @Value("${geosentinel.jwt.refresh-ttl}") Duration refreshTtl,
            @Value("${geosentinel.jwt.session-max-ttl}") Duration sessionMaxTtl,
            @Value("${geosentinel.jwt.refresh-reuse-grace}") Duration refreshReuseGrace,
            // GDPR art. 8: 14 in Italia, da 13 a 16 negli altri Stati UE
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

    // Email già registrata -> 422: rivela che l'account esiste. Scelta accettata, limitata dal rate limit su /auth/**
    // Età prima di ogni lettura o scrittura: di chi è sotto la soglia non resta nessun dato, e un minore non
    // scopre se un'email è registrata
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

    // Password attuale sbagliata -> 422, non 401: un 401 farebbe credere all'app che il token sia scaduto (refresh/logout).
    // Revoca tutte le sessioni (anche quelle di un eventuale ladro) e ne apre una nuova per chi ha cambiato la password
    @Transactional
    public AuthTokens changePassword(UUID userId, String currentPassword, String newPassword, String mfaCode) {
        // Lock sull'utente: un refresh concorrente attende e poi trova la famiglia già revocata
        User user = userRepository.findByIdForUpdate(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if(!user.hasPassword()){
            throw new BusinessRuleViolationException("Account has no password: use the password reset");
        }
        // Stesso limite per account del login: con un access token rubato (15 min) non si indovina la password
        loginAttemptLimiter.tryAcquire(sha256Hex(user.getEmail())).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleViolationException("Current password is incorrect");
        }
        // ADR-0026: apre una sessione nuova, quindi chiede il secondo fattore come il login. Altrimenti un access token
        // rubato più la password diventerebbero una sessione lunga e chiuderebbero fuori il titolare
        mfaService.verifyStepUp(userId, mfaCode);

        Instant now = clock.instant();
        user.updatePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllOfUser(userId, now);
        // Anche gli access token ancora validi degli altri dispositivi. Fino al secondo prima: la sessione nuova nasce
        // in questo secondo (iat ha la precisione del secondo) e deve restare valida
        tokenBlacklist.revokeAllIssuedUntil(userId, now.minusSeconds(1));
        return createAuthSession(user, null);
    }

    // Limite per account contro il credential stuffing (molti IP, stessa email), che il limite per IP non ferma.
    // Controllato per primo: stesso comportamento per account esistenti e non (niente enumerazione) e nessun costo BCrypt.
    // Chiave = hash dell'email, mai in chiaro su Redis. Limite accettato: chi attacca può rallentare il login di una
    // vittima tempestandone l'email; il token bucket lo attenua (un tentativo ricaricato ogni 90 s, nessun blocco fisso).
    // noRollbackFor: l'errore del codice MFA deve restare committato (contatore ed evento d'avviso)
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public AuthTokens login(String email, String rawPassword, String mfaCode) {
        String normalizedEmail = User.normalizeEmail(email);
        loginAttemptLimiter.tryAcquire(sha256Hex(normalizedEmail)).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });

        Optional<User> user = userRepository.findByEmail(normalizedEmail);
        //Account senza password, hash null.
        String storedHash = user.map(User::getPasswordHash).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, storedHash != null ? storedHash : dummyPasswordHash);
        if (user.isEmpty() || storedHash == null || !passwordMatches || !user.get().isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        // Solo dopo la password: un 401 mfa-required arriva solo a chi la conosce già
        mfaService.verifyLogin(user.get(), mfaCode);
        return createAuthSession(user.get(), null);
    }

    // noRollbackFor: la revoca della famiglia sul riuso deve restare anche se poi si risponde 401
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String hash = sha256Hex(rawRefreshToken);

        // Prima il lock sull'utente, poi il token: stesso ordine di changePassword e deleteAccount (niente deadlock).
        // Un cambio password in corso finisce prima, e la revoca dei suoi token si vede nell'UPDATE qui sotto
        UUID userId = refreshTokenRepository.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        userRepository.findByIdForUpdate(userId);

        // UPDATE atomico: solo una richiesta concorrente vince la rotazione
        int updatedRows = refreshTokenRepository.revokeIfActive(hash, now);
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (updatedRows == 0 && !acceptedInRotationGrace(oldToken, now)) {
            // token già usato -> possibile furto: si revoca l'intera famiglia
            refreshTokenRepository.revokeFamily(oldToken.getFamilyId(), now);
            throw new BadCredentialsException("Invalid refresh token");
        }

        // copre sia la scadenza del token sia quella assoluta della sessione
        if (oldToken.isExpired(now)) {
            throw new BadCredentialsException("Refresh token expired");
        }

        User user = oldToken.getUser();
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        return createAuthSession(user, oldToken);
    }

    // noRollbackFor: se Redis non risponde la blacklist lancia IllegalStateException; la revoca della famiglia
    // nel DB resta comunque (il refresh token è morto), l'access token scade da solo entro il suo TTL
    @Transactional(noRollbackFor = IllegalStateException.class)
    public void logout(Jwt accessToken, String rawRefreshToken) {
        Instant now = clock.instant();

        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String hash = sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(hash)
                    // si revoca solo una sessione dell'utente autenticato: il token di un altro utente si ignora
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

    // ADR-0019. Il client ha mandato il token vecchio perché non ha ricevuto la risposta della rotazione (rete caduta).
    // Si accetta solo se il token è stato revocato da una rotazione da non più di refreshReuseGrace e il successore
    // non è mai stato usato: lo si revoca (UPDATE atomico) e si ruota di nuovo. Un ladro che usa il token vecchio
    // nella finestra prende il posto del successore, ma al primo uso del successore da parte del client legittimo
    // la famiglia si revoca come oggi.
    private boolean acceptedInRotationGrace(RefreshToken token, Instant now) {
        return token.isInRotationGrace(now, refreshReuseGrace)
                && refreshTokenRepository.revokeIfActiveById(token.getReplacedById(), now) == 1;
    }

    // previous == null -> nuova sessione (nuova famiglia); altrimenti rotazione: stessa famiglia, stessa scadenza assoluta
    private AuthTokens createAuthSession(User user, RefreshToken previous) {
        Instant now = clock.instant();
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(user);

        UUID familyId = previous == null ? UUID.randomUUID() : previous.getFamilyId();
        Instant familyExpiresAt = previous == null ? now.plus(sessionMaxTtl) : previous.getFamilyExpiresAt();
        // il token non sopravvive alla sessione
        Instant refreshExpiresAt = min(now.plus(refreshTtl), familyExpiresAt);

        String rawRefreshToken = generateSecureRandomToken();
        RefreshToken saved = refreshTokenRepository.save(new RefreshToken(
                user, sha256Hex(rawRefreshToken), familyId, refreshExpiresAt, familyExpiresAt, now));
        if (previous != null) {
            previous.replacedBy(saved.getId());
        }

        return AuthTokens.bearer(accessToken.tokenValue(), accessToken.expiresAt(), rawRefreshToken);
    }

    // ADR-0024. Ordine dei lock come in changePassword: prima l'utente, poi i suoi token.
    // noRollbackFor come in login: con il rollback l'email d'avviso (AFTER_COMMIT) non partirebbe mai.
    // Sicuro: la verifica MFA viene prima di ogni scrittura
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public GoogleSignIn loginWithGoogle(GoogleIdentity google, LocalDate birthDate, String mfaCode) {
        // Limite per account sul sub Google, come per l'email nel login con password: stesso credential stuffing, altro canale
        loginAttemptLimiter.tryAcquire(sha256Hex("google:" + google.subject())).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });

        // Un'email non verificata non apre né crea nulla: un account Google con l'email di un altro (non verificata)
        // creerebbe un account con quell'email e, quando la vittima entra con il suo Google, l'identità
        // dell'attaccante resterebbe collegata all'account della vittima
        if (!google.emailVerified()) {
            throw new BusinessRuleViolationException("Google account email is not verified");
        }
        Instant now = clock.instant();
        String email = User.normalizeEmail(google.email());
        userIdentityRepository.lockEmail(GOOGLE_EMAIL_LOCK_NAMESPACE, email);

        // 1. Account già collegato: si entra come sempre
        Optional<UserIdentity> linked = userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject());
        if (linked.isPresent()) {
            User user = lockEnabled(linked.get().getUser().getId());
            mfaService.verifyLogin(user, mfaCode);
            return new GoogleSignIn(createAuthSession(user, null), false);
        }

        // 2. Email già usata da un account: si collega (la registrazione non verifica l'email, Google sì)
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

            // Già collegato a un altro account Google (stessa email, sub diverso): non si aggiunge una seconda identità
            if (userIdentityRepository.existsByUser_IdAndProvider(user.getId(), IdentityProvider.GOOGLE)) {
                throw new BusinessRuleViolationException("Email already linked to another Google account");
            }
            // Password azzerata e sessioni chiuse: chi aveva registrato l'email non entra più con la sua password
            user.clearPassword();
            userIdentityRepository.save(new UserIdentity(user, IdentityProvider.GOOGLE, google.subject(), email, now));
            refreshTokenRepository.revokeAllOfUser(user.getId(), now);
            // ADR-0026: anche i telefoni di chi aveva registrato l'email smettono di ricevere le push di questo account
            deviceService.deleteAllOf(user.getId());
            tokenBlacklist.revokeAllIssuedUntil(user.getId(), now.minusSeconds(1));
            return new GoogleSignIn(createAuthSession(user, null), false);
        }

        // 3. Account nuovo: le stesse regole della registrazione, età compresa
        if (birthDate == null) {
            throw new BirthDateRequiredException("Birth date is required to create an account");
        }
        requireMinAge(birthDate);
        User user = User.createHikerWithoutPassword(email, displayNameOf(google));
        user.confirmAge(now);
        User saved = userRepository.save(user);
        userIdentityRepository.save(new UserIdentity(saved, IdentityProvider.GOOGLE, google.subject(), email, now));
        return new GoogleSignIn(createAuthSession(saved, null), true);
    }

    private User lockEnabled(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }

    // Età prima di ogni lettura o scrittura: di chi è sotto la soglia non resta nessun dato
    private void requireMinAge(LocalDate birthDate) {
        if (Period.between(birthDate, LocalDate.now(clock)).getYears() < minAge) {
            throw new AgeRequirementNotMetException("Registration is not available for this age");
        }
    }

    // Il nome di Google può mancare o non rispettare i limiti del profilo (2-100 caratteri)
    private static String displayNameOf(GoogleIdentity google) {
        String name = google.displayName() == null ? "" : google.displayName().strip();
        if (name.length() < 2) {
            return "Escursionista";
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String generateSecureRandomToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

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
