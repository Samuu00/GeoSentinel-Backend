package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.RefreshToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    // TODO M2.2: spostare le durate in JwtProperties
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final Duration SESSION_MAX_TTL = Duration.ofDays(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final JwtIssuer jwtIssuer;
    private final Clock clock;
    // Hash fittizio: il login esegue sempre un confronto BCrypt, così il tempo di risposta non rivela se l'email esiste
    private final String dummyPasswordHash;

    public interface JwtIssuer { IssuedAccessToken issueAccessToken(User user); }

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenBlacklist tokenBlacklist,
            JwtIssuer jwtIssuer,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenBlacklist = tokenBlacklist;
        this.jwtIssuer = jwtIssuer;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // Email già registrata → 422: rivela che l'account esiste. Scelta accettata, limitata dal rate limit su /auth/**
    @Transactional
    public AuthTokens register(RegisterCommand cmd) {
        String email = User.normalizeEmail(cmd.email());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessRuleViolationException("Email already registered");
        }

        String encodedPassword = passwordEncoder.encode(cmd.rawPassword());
        User user = userRepository.save(User.createHiker(email, encodedPassword, cmd.displayName()));
        return createAuthSession(user, null);
    }

    @Transactional
    public AuthTokens login(String email, String rawPassword) {
        Optional<User> user = userRepository.findByEmail(User.normalizeEmail(email));
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
            // token già usato → possibile furto: si revoca l'intera famiglia
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

    // previous == null → nuova sessione (nuova famiglia); altrimenti rotazione: stessa famiglia, stessa scadenza assoluta
    private AuthTokens createAuthSession(User user, RefreshToken previous) {
        Instant now = clock.instant();
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(user);

        UUID familyId = previous == null ? UUID.randomUUID() : previous.getFamilyId();
        Instant familyExpiresAt = previous == null ? now.plus(SESSION_MAX_TTL) : previous.getFamilyExpiresAt();
        // il token non sopravvive alla sessione
        Instant refreshExpiresAt = min(now.plus(REFRESH_TOKEN_TTL), familyExpiresAt);

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
