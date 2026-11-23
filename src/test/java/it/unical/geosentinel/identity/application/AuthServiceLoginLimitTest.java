// Test unitario del limite di tentativi di login per account in AuthService, con dipendenze finte di Mockito
// e orologio fisso, senza Spring. Il limite si applica prima di cercare l'utente e di verificare la password,
// usa come chiave un hash dell'email normalizzata e non cambia il tempo costante della verifica.
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

/**
 * Verifica l'interazione tra il login e il {@link LoginAttemptLimiter}.
 */
class AuthServiceLoginLimitTest {

    /** Istante fisso restituito dall'orologio del servizio. */
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    /** Repository degli utenti, su cui si verifica se la ricerca per email avviene. */
    private final UserRepository users = mock(UserRepository.class);

    /** Encoder delle password, su cui si verifica se il confronto BCrypt avviene. */
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    /** Limitatore dei tentativi di login per account. */
    private final LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);

    /** Servizio sotto test; le dipendenze non coinvolte nel login sono finte. */
    private final AuthService service = new AuthService(
            mock(UserIdentityRepository.class), users, mock(RefreshTokenRepository.class), passwordEncoder, mock(TokenBlacklist.class),
            limiter, mock(MfaService.class), mock(DeviceService.class), mock(AuthService.JwtIssuer.class), Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(60), 14);

    /**
     * Se il limitatore rifiuta il tentativo, il login deve lanciare una {@link RateLimitExceededException} con
     * il tempo di attesa indicato dal limitatore, senza cercare l'utente e senza calcolare BCrypt.
     */
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

    /**
     * La stessa email scritta con maiuscole diverse deve produrre la stessa chiave per il limitatore, e la chiave
     * deve essere un hash esadecimale di 64 caratteri (SHA-256): in Redis non finisce l'email in chiaro.
     */
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

    /**
     * Se il limitatore lascia passare il tentativo, il login prosegue con la verifica delle credenziali: per un
     * account inesistente la risposta è {@link BadCredentialsException}, ma il confronto BCrypt viene eseguito
     * comunque.
     */
    @Test
    void allowedAttemptContinuesToCredentialCheck() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail("mario@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("mario@example.com", "sbagliata", null))
                .isInstanceOf(BadCredentialsException.class);
        // BCrypt viene calcolato anche quando l'account non esiste, così il tempo di risposta è lo stesso e
        // non rivela quali email sono registrate.
        verify(passwordEncoder).matches(any(), any());
    }
}
