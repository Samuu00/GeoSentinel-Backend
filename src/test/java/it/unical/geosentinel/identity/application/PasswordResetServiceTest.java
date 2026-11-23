// Test unitario di PasswordResetService con dipendenze finte di Mockito, senza Spring né database.
// Riguarda la prima fase del reset della password, la richiesta del codice: nel thread della richiesta HTTP
// il servizio non deve fare nulla che dipenda dall'esistenza dell'account (né query né BCrypt), così il
// tempo di risposta è lo stesso per email registrate e non e non si può scoprire chi è iscritto.
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

/**
 * Verifica che la richiesta di reset si limiti a controllare il rate limit e a pubblicare un evento, lasciando
 * il lavoro che dipende dall'account al listener in background.
 */
class PasswordResetServiceTest {

    /** Repository degli utenti: non deve essere mai interrogato durante la richiesta. */
    private final UserRepository users = mock(UserRepository.class);

    /** Repository dei token di reset: non deve essere mai usato durante la richiesta. */
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);

    /** Limitatore delle richieste di reset per email. */
    private final PasswordResetLimiter limiter = mock(PasswordResetLimiter.class);

    /** Encoder delle password: non deve calcolare hash durante la richiesta. */
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    /** Publisher degli eventi applicativi, su cui si verifica l'evento di richiesta. */
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    /** Servizio sotto test, con orologio fisso, codici validi 15 minuti e al massimo 5 tentativi. */
    private final PasswordResetService service = new PasswordResetService(
            users, resetTokens, mock(RefreshTokenRepository.class), mock(TokenBlacklist.class), limiter,
            mock(LoginAttemptLimiter.class), mock(MfaAttemptLimiter.class), passwordEncoder, events,
            Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 5);

    /**
     * Con il rate limit non superato, la richiesta deve pubblicare un {@link PasswordResetRequested} con
     * l'email normalizzata (spazi tolti, minuscole) e non deve toccare né i repository né l'encoder.
     */
    @Test
    void requestOnlyChecksTheLimitAndPublishes() {
        // Il costruttore del servizio calcola un hash fittizio con l'encoder: quella chiamata non va contata.
        clearInvocations(passwordEncoder);
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.empty());

        service.requestReset(" Hiker@GeoSentinel.dev ");

        verify(events).publishEvent(new PasswordResetRequested("hiker@geosentinel.dev"));
        verifyNoInteractions(users, resetTokens, passwordEncoder);
    }

    /**
     * Con il rate limit superato, la richiesta deve lanciare una {@link RateLimitExceededException} senza
     * pubblicare alcun evento, quindi senza inviare email.
     */
    @Test
    void limitedRequestPublishesNothing() {
        when(limiter.tryAcquire(anyString())).thenReturn(Optional.of(Duration.ofMinutes(20)));

        assertThatThrownBy(() -> service.requestReset("hiker@geosentinel.dev"))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(events);
    }
}
