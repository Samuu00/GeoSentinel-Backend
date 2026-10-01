package it.unical.geosentinel.shared;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Profilo prod (M7.6) sopra quello di test: le richieste arrivano da 127.0.0.1, un proxy interno per Tomcat,
// quindi qui il test fa la parte di Caddy e manda gli header X-Forwarded-*
@ActiveProfiles("prod")
class ProdProfileIT extends AbstractIntegrationTest {

    @Test
    void rateLimitCountsTheForwardedClientIpNotTheProxy() {
        Map<String, String> first = Map.of("X-Forwarded-For", "203.0.113.10");
        Map<String, String> second = Map.of("X-Forwarded-For", "203.0.113.20");

        // Esaurisce il bucket di login/register del primo client (register non validi: 400 immediati)
        HttpResult last = null;
        for (int i = 0; i < 1_000; i++) {
            last = call(HttpMethod.POST, "/api/v1/auth/register", null, "{}", first);
            if (last.status() == 429) {
                break;
            }
        }
        assertThat(last.status()).isEqualTo(429);

        // Un altro client dietro lo stesso proxy ha il suo bucket
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null, "{}", second).status()).isEqualTo(400);
    }

    @Test
    void generatedUrlsUseTheForwardedScheme() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", obtainToken(Role.HIKER), """
                {"type":"FALLEN_TREE","severity":"LOW","location":{"lat":39.30,"lon":16.40}}
                """, Map.of("X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.30"));

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.headers().getFirst(HttpHeaders.LOCATION)).startsWith("https://");
    }

    @Test
    void apiDocsAreOffAndHealthIsPublicWithoutDetails() {
        assertThat(call(HttpMethod.GET, "/v3/api-docs", null, null).status()).isEqualTo(404);

        HttpResult health = call(HttpMethod.GET, "/actuator/health", null, null);
        assertThat(health.status()).isEqualTo(200);
        // "groups" (liveness/readiness) sono solo nomi: niente components né details (DB, Redis, versioni)
        assertThat(json(health.body()).get("status").asString()).isEqualTo("UP");
        assertThat(json(health.body()).propertyNames()).doesNotContain("components", "details");
    }
}
