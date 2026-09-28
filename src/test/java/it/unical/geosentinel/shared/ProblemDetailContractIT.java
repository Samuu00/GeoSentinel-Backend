package it.unical.geosentinel.shared;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Contratto degli errori (RFC 7807): qualunque sia la sorgente (validazione, filtri di sicurezza, rate limit,
// service), la risposta è application/problem+json con type, title, status, instance, timestamp e traceId
class ProblemDetailContractIT extends AbstractIntegrationTest {

    @Test
    void validationErrorIs400WithFieldErrors() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, "{\"email\":\"non-una-email\"}");

        JsonNode problem = assertProblem(result, 400, "validation-error", "/api/v1/auth/register");
        assertThat(problem.get("errors").size()).isPositive();
    }

    @Test
    void latitudeOutOfRangeIs400() {
        assertProblem(call(HttpMethod.GET, "/api/v1/trails/nearby?lon=16.4&lat=200", null, null),
                400, "validation-error", "/api/v1/trails/nearby");
    }

    @Test
    void missingTokenIs401() {
        assertProblem(call(HttpMethod.GET, "/api/v1/bookings/my", null, null), 401, "unauthorized", "/api/v1/bookings/my");
    }

    @Test
    void wrongRoleIs403() {
        assertProblem(call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.HIKER), """
                {"name":"Rifugio","location":{"lat":39.28,"lon":16.45},"capacity":10}
                """), 403, "forbidden", "/api/v1/refuges");
    }

    @Test
    void unknownResourceIs404() {
        String path = "/api/v1/trails/" + UUID.randomUUID();
        assertProblem(call(HttpMethod.GET, path, null, null), 404, "not-found", path);
    }

    @Test
    void fullRefugeIs409() {
        UUID refugeId = insertRefuge(1, createUser(Role.PARK_MANAGER).getId());
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        String body = """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1));
        assertThat(book(obtainToken(Role.HIKER), body).status()).isEqualTo(201);

        assertProblem(book(obtainToken(Role.HIKER), body), 409, "capacity-exceeded", "/api/v1/bookings");
    }

    @Test
    void missingIdempotencyKeyIs428() {
        assertProblem(call(HttpMethod.POST, "/api/v1/bookings", obtainToken(Role.HIKER), """
                {"refugeId":"%s","checkIn":"2030-01-10","checkOut":"2030-01-11","guests":1}
                """.formatted(UUID.randomUUID())), 428, "precondition-required", "/api/v1/bookings");
    }

    @Test
    void tooManyRequestsIs429() {
        String login = "{\"email\":\"nessuno@test.dev\",\"password\":\"sbagliata\"}";
        for (int i = 0; i < 5; i++) {
            call(HttpMethod.POST, "/api/v1/auth/login", null, login);
        }

        assertProblem(call(HttpMethod.POST, "/api/v1/auth/login", null, login), 429, "rate-limit-exceeded", "/api/v1/auth/login");
    }

    private HttpResult book(String token, String body) {
        return call(HttpMethod.POST, "/api/v1/bookings", token, body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private JsonNode assertProblem(HttpResult result, int status, String typeSlug, String instance) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(isProblemJson(result)).isTrue();
        JsonNode problem = json(result.body());
        assertThat(problem.get("type").asString()).isEqualTo("https://geosentinel.dev/problems/" + typeSlug);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asString()).isNotBlank();
        assertThat(problem.get("instance").asString()).isEqualTo(instance);
        assertThat(problem.has("timestamp")).isTrue();
        assertThat(problem.get("traceId").asString()).matches("[0-9a-f]{32}");
        return problem;
    }
}
