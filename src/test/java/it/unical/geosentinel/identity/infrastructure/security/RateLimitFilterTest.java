package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.infrastructure.redis.RateLimitDecision;
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

    // regressione: con una chiave condivisa le GET pubbliche ricaricavano il bucket del login (bypass del limite anti brute-force)
    @Test
    void authRoutesUseTheirOwnBucket() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryConsume(any(), any())).thenReturn(RateLimitDecision.allowed(1));
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC());

        filter.doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("/api/v1/trails/nearby"), new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(limiter, times(2)).tryConsume(keys.capture(), any());
        assertThat(keys.getAllValues()).containsExactly("rl:auth:ip:10.0.0.1", "rl:ip:10.0.0.1");
    }

    @Test
    void sosIsNeverRateLimited() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(ProblemDetailWriter.class), Clock.systemUTC());
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
