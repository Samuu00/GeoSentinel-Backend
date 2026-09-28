package it.unical.geosentinel.identity;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

// Token bucket su Redis (script Lua): 5 richieste al minuto per IP su /auth/**
class RateLimitIT extends AbstractIntegrationTest {

    private static final String WRONG_LOGIN = """
            {"email":"nessuno@test.dev","password":"sbagliata"}
            """;

    @Test
    void sixthLoginFromTheSameIpGets429WithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            HttpResult attempt = login();
            assertThat(attempt.status()).isEqualTo(401);
            assertThat(attempt.headers().getFirst("X-RateLimit-Remaining")).isEqualTo(String.valueOf(4 - i));
        }

        HttpResult blocked = login();

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(json(blocked.body()).get("type").asString()).endsWith("/rate-limit-exceeded");
    }

    // Un bucket per policy: le GET pubbliche (60/min) non devono ricaricare il bucket del login (5/min)
    @Test
    void publicRequestsDoNotRefillTheAuthBucket() {
        for (int i = 0; i < 5; i++) {
            login();
        }
        for (int i = 0; i < 3; i++) {
            assertThat(call(HttpMethod.GET, "/api/v1/trails?bbox=16.4,39.2,16.6,39.4", null, null).status()).isEqualTo(200);
        }

        assertThat(login().status()).isEqualTo(429);
    }

    private HttpResult login() {
        return call(HttpMethod.POST, "/api/v1/auth/login", null, WRONG_LOGIN);
    }
}
