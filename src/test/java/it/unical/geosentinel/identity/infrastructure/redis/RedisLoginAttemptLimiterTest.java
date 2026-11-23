// Test unitario di RedisLoginAttemptLimiter, il limite dei tentativi di login a due livelli (account e IP, più
// account su tutti gli IP). Il limitatore Redis è un mock di Mockito e l'IP del client arriva da una richiesta
// finta messa nel RequestContextHolder, come farebbe Spring durante una richiesta vera.
package it.unical.geosentinel.identity.infrastructure.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica le chiavi e l'ordine dei due secchi di {@link RedisLoginAttemptLimiter}.
 */
class RedisLoginAttemptLimiterTest {

    /** Limitatore Redis finto. */
    private final RedisRateLimiter redis = mock(RedisRateLimiter.class);

    /** Limitatore sotto test. */
    private final RedisLoginAttemptLimiter limiter = new RedisLoginAttemptLimiter(redis);

    /** Toglie la richiesta finta dal thread, per non influenzare gli altri test. */
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Con il secchio dell'IP ancora pieno si consuma anche quello dell'account: le chiavi sono
     * {@code rl:login:<account>:<ip>} e {@code rl:login:<account>}.
     */
    @Test
    void allowedAttemptConsumesBothBuckets() {
        requestFrom("203.0.113.7");
        when(redis.tryConsume(any(), any())).thenReturn(RateLimitDecision.allowed(5));

        assertThat(limiter.tryAcquire("abc")).isEmpty();

        verify(redis).tryConsume(eq("rl:login:abc:203.0.113.7"), any());
        verify(redis).tryConsume(eq("rl:login:abc"), any());
    }

    /**
     * Se il secchio dell'IP è vuoto il tentativo è rifiutato e quello dell'account non viene toccato: chi
     * sbaglia da un IP non consuma i tentativi del titolare che entra da un altro.
     */
    @Test
    void rejectedSourceDoesNotTouchTheAccountBucket() {
        requestFrom("203.0.113.7");
        when(redis.tryConsume(eq("rl:login:abc:203.0.113.7"), any())).thenReturn(RateLimitDecision.rejected(90_000));

        assertThat(limiter.tryAcquire("abc")).contains(Duration.ofSeconds(90));

        verify(redis, never()).tryConsume(eq("rl:login:abc"), any());
    }

    /** Il reset svuota il secchio dell'IP corrente e quello dell'account. */
    @Test
    void resetClearsBothBuckets() {
        requestFrom("203.0.113.7");

        limiter.reset("abc");

        verify(redis).reset("rl:login:abc:203.0.113.7");
        verify(redis).reset("rl:login:abc");
    }

    /**
     * Mette nel thread corrente una richiesta finta con l'indirizzo indicato.
     *
     * @param remoteAddr indirizzo del client
     */
    private static void requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
