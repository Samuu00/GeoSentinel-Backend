// Test unitario di RateLimitFilter, il filtro HTTP che limita il numero di richieste per indirizzo IP usando
// bucket salvati in Redis. Il limitatore Redis è sostituito da un mock di Mockito e le richieste sono
// MockHttpServletRequest, quindi non servono né Spring né Redis.
package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.infrastructure.redis.RateLimitDecision;
import it.unical.geosentinel.identity.infrastructure.redis.RateLimitPolicy;
import it.unical.geosentinel.identity.infrastructure.redis.RedisRateLimiter;
import it.unical.geosentinel.shared.web.ProblemDetailWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica quale bucket e quale politica il filtro usa per ciascun gruppo di rotte, e che l'SOS non venga mai
 * limitato.
 */
class RateLimitFilterTest {

    /** Capacità dei bucket per minuto: 30 per login, registrazione e logout, 120 per il refresh, 60 per il resto dell'API. */
    private static final RateLimitProperties LIMITS = new RateLimitProperties(30, 120, 60);

    /**
     * Le rotte di autenticazione devono avere un bucket separato da quello generale. Se la chiave fosse
     * condivisa, le chiamate pubbliche consumerebbero e ricaricherebbero lo stesso bucket del login, rendendo
     * inefficace il limite contro i tentativi di indovinare le password.
     */
    @Test
    void authRoutesUseTheirOwnBucket() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryConsume(any(), any())).thenReturn(RateLimitDecision.allowed(1));
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC(), LIMITS);

        filter.doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/trails/nearby"), new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(limiter, times(2)).tryConsume(keys.capture(), any());
        assertThat(keys.getAllValues()).containsExactly("rl:auth:ip:10.0.0.1", "rl:ip:10.0.0.1");
    }

    /**
     * Login, refresh e resto dell'API devono usare tre bucket distinti, ciascuno con la propria capacità. Il
     * refresh ha un bucket proprio perché dietro un CGNAT molti utenti condividono lo stesso IP e i loro refresh
     * frequenti non devono consumare i tentativi di login.
     */
    @Test
    void eachRouteGroupUsesItsConfiguredPolicy() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryConsume(any(), any())).thenReturn(RateLimitDecision.allowed(1));
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC(), LIMITS);

        filter.doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/auth/refresh"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/trails/nearby"), new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RateLimitPolicy> policies = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter, times(3)).tryConsume(keys.capture(), policies.capture());
        assertThat(keys.getAllValues()).containsExactly("rl:auth:ip:10.0.0.1", "rl:refresh:ip:10.0.0.1", "rl:ip:10.0.0.1");
        assertThat(policies.getAllValues()).extracting(RateLimitPolicy::capacity).containsExactly(30L, 120L, 60L);
        // Se Redis non risponde, le rotte di autenticazione restano bloccate (fail-closed) per sicurezza,
        // mentre il resto dell'API lascia passare le richieste.
        assertThat(policies.getAllValues()).extracting(RateLimitPolicy::failClosed).containsExactly(true, true, false);
    }

    /**
     * Una richiesta di SOS non deve mai passare dal limitatore e deve sempre arrivare alla catena dei filtri:
     * una richiesta di soccorso non può essere rifiutata per troppe richieste.
     */
    @Test
    void sosIsNeverRateLimited() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC(), LIMITS);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/sos"), new MockHttpServletResponse(), chain);

        verify(limiter, times(0)).tryConsume(any(), any());
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * Costruisce una richiesta POST finta proveniente sempre dallo stesso IP.
     *
     * @param uri il percorso della richiesta
     * @return la richiesta con indirizzo remoto {@code 10.0.0.1}
     */
    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
