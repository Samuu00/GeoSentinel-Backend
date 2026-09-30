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

class RateLimitFilterTest {

    private static final RateLimitProperties LIMITS = new RateLimitProperties(30, 120, 60);

    // regressione: con una chiave condivisa le GET pubbliche ricaricavano il bucket del login (bypass del limite anti brute-force)
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

    // M7.3: sotto CGNAT i refresh sono frequenti e non devono consumare i tentativi di login dello stesso IP
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
        // Redis giù: /auth/** resta limitato (fail-closed), il resto dell'API no
        assertThat(policies.getAllValues()).extracting(RateLimitPolicy::failClosed).containsExactly(true, true, false);
    }

    @Test
    void sosIsNeverRateLimited() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC(), LIMITS);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/sos"), new MockHttpServletResponse(), chain);

        verify(limiter, times(0)).tryConsume(any(), any());
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }
}
