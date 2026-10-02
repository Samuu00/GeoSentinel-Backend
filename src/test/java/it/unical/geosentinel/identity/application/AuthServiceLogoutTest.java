package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.RefreshToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceLogoutTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AuthService service = new AuthService(
            mock(UserRepository.class), refreshTokens, mock(PasswordEncoder.class), mock(TokenBlacklist.class),
            mock(LoginAttemptLimiter.class), mock(AuthService.JwtIssuer.class), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(60), 14);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();

    private void storedTokenOwnedBy(UUID userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        RefreshToken token = mock(RefreshToken.class);
        when(token.getUser()).thenReturn(user);
        when(token.getFamilyId()).thenReturn(familyId);
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
    }

    private Jwt jwtFor(UUID subject) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(subject.toString())
                .jti("jti").issuedAt(NOW).expiresAt(NOW.plusSeconds(600)).build();
    }

    @Test
    void revocaLaFamigliaDelProprioToken() {
        storedTokenOwnedBy(ownerId);
        service.logout(jwtFor(ownerId), "raw");
        verify(refreshTokens).revokeFamily(familyId, NOW);
    }

    @Test
    void ignoraIlTokenDiUnAltroUtente() {
        storedTokenOwnedBy(ownerId);
        service.logout(jwtFor(UUID.randomUUID()), "raw");
        verify(refreshTokens, never()).revokeFamily(any(), any());
    }
}
