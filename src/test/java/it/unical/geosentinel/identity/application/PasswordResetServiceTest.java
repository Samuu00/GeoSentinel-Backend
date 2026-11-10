package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.MfaAttemptLimiter;
import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.PasswordResetLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.infrastructure.persistence.PasswordResetTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// La richiesta non deve fare nel thread HTTP nulla che dipenda dall'esistenza dell'account (né DB né BCrypt):
// è ciò che rende il tempo di risposta uguale per email registrate e non
class PasswordResetServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
    private final PasswordResetLimiter limiter = mock(PasswordResetLimiter.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PasswordResetService service = new PasswordResetService(
            users, resetTokens, mock(RefreshTokenRepository.class), mock(TokenBlacklist.class), limiter,
            mock(LoginAttemptLimiter.class), mock(MfaAttemptLimiter.class), passwordEncoder, events,
            Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 5);

    @Test
    void requestOnlyChecksTheLimitAndPublishes() {
        clearInvocations(passwordEncoder); // il costruttore calcola l'hash fittizio
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.empty());

        service.requestReset(" Hiker@GeoSentinel.dev ");

        verify(events).publishEvent(new PasswordResetRequested("hiker@geosentinel.dev"));
        verifyNoInteractions(users, resetTokens, passwordEncoder);
    }

    @Test
    void limitedRequestPublishesNothing() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.of(Duration.ofMinutes(20)));

        assertThatThrownBy(() -> service.requestReset("hiker@geosentinel.dev"))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(events);
    }
}
