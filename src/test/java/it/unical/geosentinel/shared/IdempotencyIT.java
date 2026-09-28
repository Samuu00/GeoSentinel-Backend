package it.unical.geosentinel.shared;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// D12: i retry del client (stessa Idempotency-Key) non creano duplicati, nemmeno se arrivano insieme
class IdempotencyIT extends AbstractIntegrationTest {

    private static final String SOS_BODY = """
            {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"Caviglia"}
            """;

    @RepeatedTest(5)
    void tenSimultaneousSosRetriesCreateOneAlert() throws Exception {
        String hiker = obtainToken(Role.HIKER);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/sos", hiker, SOS_BODY, key));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("sos_alerts")).isEqualTo(1);
    }

    // Senza chiave l'advisory lock per escursionista fa lo stesso lavoro: un SOS non si duplica
    @RepeatedTest(5)
    void tenSimultaneousSosWithoutKeyCreateOneAlert() throws Exception {
        String hiker = obtainToken(Role.HIKER);

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/sos", hiker, SOS_BODY));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("sos_alerts")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void tenSimultaneousBookingRetriesCreateOneBooking() throws Exception {
        String hiker = obtainToken(Role.HIKER);
        String body = bookingBody(insertRefuge(10, createUser(Role.PARK_MANAGER).getId()), 1);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/bookings", hiker, body, key));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBookingIsRejected() {
        String hiker = obtainToken(Role.HIKER);
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call(HttpMethod.POST, "/api/v1/bookings", hiker, bookingBody(refugeId, 1), key).status()).isEqualTo(201);

        HttpResult reused = call(HttpMethod.POST, "/api/v1/bookings", hiker, bookingBody(refugeId, 2), key);

        assertThat(reused.status()).isEqualTo(422);
        assertThat(json(reused.body()).get("type").asString()).endsWith("/idempotency-key-reused");
    }

    // Un SOS non si rifiuta mai: stessa chiave con un body diverso → l'allerta già creata
    @Test
    void sameKeyWithADifferentSosReturnsTheExistingAlert() {
        String hiker = obtainToken(Role.HIKER);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        HttpResult first = call(HttpMethod.POST, "/api/v1/sos", hiker, SOS_BODY, key);

        HttpResult second = call(HttpMethod.POST, "/api/v1/sos", hiker, """
                {"location":{"lat":39.3100,"lon":16.5100},"medicalNotes":"Altro"}
                """, key);

        assertThat(second.status()).isEqualTo(200);
        assertThat(id(second)).isEqualTo(id(first));
    }

    private void assertOneCreatedAndNineReplays(List<HttpResult> results) {
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(9);
        assertThat(results.stream().map(this::id).distinct()).hasSize(1);
    }

    private String bookingBody(UUID refugeId, int guests) {
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        return """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":%d}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1), guests);
    }

    private String id(HttpResult result) {
        return json(result.body()).get("id").asString();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
