package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserIdentityRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceLoginLimitTest {
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);
    private final AuthService service = new AuthService(
            mock(UserIdentityRepository.class), users, mock(RefreshTokenRepository.class), passwordEncoder, mock(TokenBlacklist.class),
            limiter, mock(MfaService.class), mock(DeviceService.class), mock(AuthService.JwtIssuer.class), Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(60), 14);

    @Test
    void rejectedAttemptSkipsLookupAndBcrypt() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.of(Duration.ofSeconds(90)));

        assertThatThrownBy(() -> service.login("mario@example.com", "password-qualsiasi", null))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(90));

        verify(users, never()).findByEmail(any());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void keyIsHashOfNormalizedEmail() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.of(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> service.login("Mario@Example.COM", "x", null)).isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> service.login("mario@example.com", "x", null)).isInstanceOf(RateLimitExceededException.class);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(limiter, times(2)).tryAcquire(keys.capture());
        List<String> captured = keys.getAllValues();
        assertThat(captured.get(0)).isEqualTo(captured.get(1)).matches("[0-9a-f]{64}");
    }

    @Test
    void allowedAttemptContinuesToCredentialCheck() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail("mario@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("mario@example.com", "sbagliata", null))
                .isInstanceOf(BadCredentialsException.class);
        // tempo costante: BCrypt gira anche per un account inesistente
        verify(passwordEncoder).matches(any(), any());
    }
}
