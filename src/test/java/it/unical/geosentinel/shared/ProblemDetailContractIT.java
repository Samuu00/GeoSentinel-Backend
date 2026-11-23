// Test di integrazione del formato degli errori, con chiamate HTTP vere e PostGIS e Redis in Testcontainers.
// Qualunque sia l'origine dell'errore (validazione, filtri di sicurezza, rate limit, logica dei servizi), la
// risposta deve essere un ProblemDetail RFC 7807 in application/problem+json con i campi type, title, status,
// instance, timestamp e traceId. L'app mobile si basa sullo slug in "type" per riconoscere l'errore.
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

/**
 * Provoca un errore per ogni codice di stato significativo e verifica che il corpo rispetti sempre lo stesso
 * contratto.
 */
class ProblemDetailContractIT extends AbstractIntegrationTest {

    /** Un corpo di registrazione non valido deve dare 400 {@code validation-error} con l'elenco degli errori per campo. */
    @Test
    void validationErrorIs400WithFieldErrors() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, "{\"email\":\"non-una-email\"}");

        JsonNode problem = assertProblem(result, 400, "validation-error", "/api/v1/auth/register");
        assertThat(problem.get("errors").size()).isPositive();
    }

    /** Una latitudine fuori dall'intervallo in un parametro di query deve dare 400 {@code validation-error}. */
    @Test
    void latitudeOutOfRangeIs400() {
        assertProblem(call(HttpMethod.GET, "/api/v1/trails/nearby?lon=16.4&lat=200", null, null),
                400, "validation-error", "/api/v1/trails/nearby");
    }

    /** Una rotta protetta chiamata senza token deve dare 401 {@code unauthorized}. */
    @Test
    void missingTokenIs401() {
        assertProblem(call(HttpMethod.GET, "/api/v1/bookings/my", null, null), 401, "unauthorized", "/api/v1/bookings/my");
    }

    /** Un escursionista che prova a creare un rifugio (azione da gestore) deve ricevere 403 {@code forbidden}. */
    @Test
    void wrongRoleIs403() {
        assertProblem(call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.HIKER), """
                {"name":"Rifugio","location":{"lat":39.28,"lon":16.45},"capacity":10}
                """), 403, "forbidden", "/api/v1/refuges");
    }

    /** Un sentiero con un id inesistente deve dare 404 {@code not-found}. */
    @Test
    void unknownResourceIs404() {
        String path = "/api/v1/trails/" + UUID.randomUUID();
        assertProblem(call(HttpMethod.GET, path, null, null), 404, "not-found", path);
    }

    /**
     * In un rifugio con un solo posto, la prima prenotazione riesce e la seconda per la stessa notte deve dare
     * 409 {@code capacity-exceeded}.
     */
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

    /** Una prenotazione senza header Idempotency-Key deve dare 428 {@code precondition-required}. */
    @Test
    void missingIdempotencyKeyIs428() {
        assertProblem(call(HttpMethod.POST, "/api/v1/bookings", obtainToken(Role.HIKER), """
                {"refugeId":"%s","checkIn":"2030-01-10","checkOut":"2030-01-11","guests":1}
                """.formatted(UUID.randomUUID())), 428, "precondition-required", "/api/v1/bookings");
    }

    /**
     * Dopo aver esaurito il rate limit delle rotte di autenticazione si deve ricevere 429
     * {@code rate-limit-exceeded}. Questo errore lo scrive direttamente il RateLimitFilter, fuori dal
     * GlobalExceptionHandler, quindi il test controlla che anche quel percorso rispetti il contratto.
     */
    @Test
    void tooManyRequestsIs429() {
        exhaustAuthRateLimit();

        assertProblem(call(HttpMethod.POST, "/api/v1/auth/register", null, "{}"), 429, "rate-limit-exceeded", "/api/v1/auth/register");
    }

    /**
     * Invia una prenotazione con una Idempotency-Key nuova.
     *
     * @param token l'access token dell'escursionista
     * @param body  il corpo JSON della prenotazione
     * @return la risposta del server
     */
    private HttpResult book(String token, String body) {
        return call(HttpMethod.POST, "/api/v1/bookings", token, body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    /**
     * Controlla che la risposta sia un ProblemDetail completo: stato, content type, type con lo slug atteso,
     * titolo non vuoto, instance uguale al percorso chiamato, timestamp presente e traceId di 32 cifre
     * esadecimali (l'id della traccia W3C).
     *
     * @param result   la risposta da controllare
     * @param status   lo stato HTTP atteso
     * @param typeSlug la parte finale attesa dell'URI in {@code type}
     * @param instance il percorso atteso in {@code instance}
     * @return il corpo JSON, per eventuali controlli aggiuntivi
     */
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
