package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.infrastructure.security.RateLimitProperties;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Token bucket su Redis (script Lua), M7.3: tre policy con bucket separati. Tutte le richieste del test
// arrivano dallo stesso IP, come più telefoni dietro il CGNAT di un operatore
class RateLimitIT extends AbstractIntegrationTest {

    private static final String TRAILS_BBOX = "/api/v1/trails?bbox=16.4,39.2,16.6,39.4";

    @Autowired
    private RateLimitProperties limits;

    // CGNAT: 20 escursionisti diversi sbagliano la password dallo stesso IP e nessuno riceve 429
    @Test
    void wrongLoginsFromManyAccountsBehindTheSameIpAreNotLimited() {
        for (int i = 0; i < 20; i++) {
            assertThat(login("utente-" + i + "@test.dev").status()).isEqualTo(401);
        }
    }

    // il brute force su un account lo ferma il limite per account (10 ogni 15 minuti), non quello per IP
    @Test
    void eleventhWrongLoginOnTheSameAccountGets429() {
        for (int i = 0; i < 10; i++) {
            assertThat(login("vittima@test.dev").status()).isEqualTo(401);
        }

        HttpResult blocked = login("vittima@test.dev");

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(json(blocked.body()).get("type").asString()).endsWith("/rate-limit-exceeded");
        // il bucket per IP ha ancora gettoni: il 429 arriva dal limite per account
        assertThat(Long.parseLong(blocked.headers().getFirst("X-RateLimit-Remaining"))).isPositive();
        // un altro account dallo stesso IP non ne risente
        assertThat(login("altro@test.dev").status()).isEqualTo(401);
    }

    // tetto per IP su login e register: la richiesta oltre il limite configurato riceve 429 con Retry-After
    @Test
    void authRequestsBeyondTheIpLimitGet429() {
        exhaustAuthRateLimit();

        HttpResult blocked = invalidRegister();

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(isProblemJson(blocked)).isTrue();
    }

    // Un bucket per policy: le GET pubbliche non devono ricaricare il bucket del login
    @Test
    void publicRequestsDoNotRefillTheAuthBucket() {
        exhaustAuthRateLimit();
        for (int i = 0; i < 3; i++) {
            assertThat(call(HttpMethod.GET, TRAILS_BBOX, null, null).status()).isEqualTo(200);
        }

        assertThat(invalidRegister().status()).isEqualTo(429);
    }

    // CGNAT: tanti refresh dallo stesso IP (utenti diversi) non vengono bloccati, e non consumano il bucket del login
    @Test
    void refreshHasItsOwnBucketLargerThanTheLoginOne() {
        exhaustAuthRateLimit();

        for (int i = 0; i < 50; i++) {
            HttpResult refresh = call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                    "{\"refreshToken\":\"%s\"}".formatted(UUID.randomUUID()));
            assertThat(refresh.status()).isEqualTo(401);
        }
    }

    // con il bearer anche le GET pubbliche contano per utente: due utenti dietro lo stesso IP non si consumano i gettoni
    @Test
    void authenticatedRequestsAreCountedPerUserNotPerIp() {
        String first = obtainToken(Role.HIKER);
        String second = obtainToken(Role.HIKER);
        long full = limits.generalPerMinute();

        long firstUserAfterOne = remaining(call(HttpMethod.GET, TRAILS_BBOX, first, null));
        long firstUserAfterTwo = remaining(call(HttpMethod.GET, TRAILS_BBOX, first, null));

        assertThat(firstUserAfterOne).isEqualTo(full - 1);
        assertThat(firstUserAfterTwo).isLessThan(firstUserAfterOne);
        // bucket nuovi: né il secondo utente né l'anonimo (per IP) risentono delle richieste del primo
        assertThat(remaining(call(HttpMethod.GET, TRAILS_BBOX, second, null))).isEqualTo(full - 1);
        assertThat(remaining(call(HttpMethod.GET, TRAILS_BBOX, null, null))).isEqualTo(full - 1);
    }

    private HttpResult invalidRegister() {
        return call(HttpMethod.POST, "/api/v1/auth/register", null, "{}");
    }

    private HttpResult login(String email) {
        return call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, "x" + TEST_PASSWORD));
    }

    private static long remaining(HttpResult result) {
        assertThat(result.status()).isEqualTo(200);
        return Long.parseLong(result.headers().getFirst("X-RateLimit-Remaining"));
    }
}
