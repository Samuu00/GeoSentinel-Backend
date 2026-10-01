package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.RefreshToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.AgeRequirementNotMetException;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final JwtIssuer jwtIssuer;
    private final Clock clock;
    private final Duration refreshTtl;
    // scadenza assoluta della sessione: le rotazioni non la estendono
    private final Duration sessionMaxTtl;
    private final int minAge;
    // Hash fittizio: il login esegue sempre un confronto BCrypt, così il tempo di risposta non rivela se l'email esiste
    private final String dummyPasswordHash;

    public interface JwtIssuer { IssuedAccessToken issueAccessToken(User user); }

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenBlacklist tokenBlacklist,
            LoginAttemptLimiter loginAttemptLimiter,
            JwtIssuer jwtIssuer,
            Clock clock,
            // @Value e non JwtProperties: application non dipende da infrastructure
            @Value("${geosentinel.jwt.refresh-ttl}") Duration refreshTtl,
            @Value("${geosentinel.jwt.session-max-ttl}") Duration sessionMaxTtl,
            // GDPR art. 8: 14 in Italia, da 13 a 16 negli altri Stati UE
            @Value("${geosentinel.registration.min-age}") int minAge
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklist = tokenBlacklist;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.jwtIssuer = jwtIssuer;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
        this.sessionMaxTtl = sessionMaxTtl;
        this.minAge = minAge;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // Email già registrata -> 422: rivela che l'account esiste. Scelta accettata, limitata dal rate limit su /auth/**
    // Età prima di ogni lettura o scrittura: di chi è sotto la soglia non resta nessun dato, e un minore non
    // scopre se un'email è registrata
    @Transactional
    public AuthTokens register(RegisterCommand cmd) {
        if (Period.between(cmd.birthDate(), LocalDate.now(clock)).getYears() < minAge) {
            throw new AgeRequirementNotMetException("Registration is not available for this age");
        }

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
    public AuthTokens changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        // Stesso limite per account del login: con un access token rubato (15 min) non si indovina la password
        loginAttemptLimiter.tryAcquire(sha256Hex(user.getEmail())).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleViolationException("Current password is incorrect");
        }

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
    @Transactional
    public AuthTokens login(String email, String rawPassword) {
        String normalizedEmail = User.normalizeEmail(email);
        loginAttemptLimiter.tryAcquire(sha256Hex(normalizedEmail)).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });

        Optional<User> user = userRepository.findByEmail(normalizedEmail);
        // matches() gira anche se l'utente non esiste: stesso costo, stesso errore
        boolean passwordMatches = passwordEncoder.matches(rawPassword,
                user.map(User::getPasswordHash).orElse(dummyPasswordHash));

        if (user.isEmpty() || !passwordMatches || !user.get().isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return createAuthSession(user.get(), null);
    }

    // noRollbackFor: la revoca della famiglia sul riuso deve restare anche se poi si risponde 401
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String hash = sha256Hex(rawRefreshToken);

        // UPDATE atomico: solo una richiesta concorrente vince la rotazione
        int updatedRows = refreshTokenRepository.revokeIfActive(hash, now);
        if (updatedRows == 0) {
            // token già usato -> possibile furto: si revoca l'intera famiglia
            refreshTokenRepository.findByTokenHash(hash)
                    .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));
            throw new BadCredentialsException("Invalid refresh token");
        }

        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

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

    // previous == null -> nuova sessione (nuova famiglia); altrimenti rotazione: stessa famiglia, stessa scadenza assoluta
    private AuthTokens createAuthSession(User user, RefreshToken previous) {
        Instant now = clock.instant();
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(user);

        UUID familyId = previous == null ? UUID.randomUUID() : previous.getFamilyId();
        Instant familyExpiresAt = previous == null ? now.plus(sessionMaxTtl) : previous.getFamilyExpiresAt();
        // il token non sopravvive alla sessione
        Instant refreshExpiresAt = min(now.plus(refreshTtl), familyExpiresAt);

        String rawRefreshToken = generateSecureRandomToken();
        refreshTokenRepository.save(new RefreshToken(
                user, sha256Hex(rawRefreshToken), familyId, refreshExpiresAt, familyExpiresAt, now));

        return AuthTokens.bearer(accessToken.tokenValue(), accessToken.expiresAt(), rawRefreshToken);
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
