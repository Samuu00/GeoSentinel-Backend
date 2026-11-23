// Test di integrazione dell'idempotenza delle richieste che creano risorse (SOS e prenotazioni), con chiamate
// HTTP vere e PostGIS e Redis in Testcontainers. In montagna la rete è instabile e l'app ripete le richieste
// con la stessa Idempotency-Key: questi nuovi tentativi non devono creare duplicati, nemmeno quando arrivano
// al server tutti insieme.
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

/**
 * Verifica che richieste ripetute e concorrenti producano una sola risorsa, e come viene trattata una chiave
 * riusata con un corpo diverso.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    /** Corpo di una richiesta SOS con posizione e nota medica. */
    private static final String SOS_BODY = """
            {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"Caviglia"}
            """;

    /**
     * Dieci invii simultanei dello stesso SOS con la stessa chiave devono creare una sola allerta: una risposta
     * 201 e nove 200 che restituiscono la stessa allerta.
     */
    @RepeatedTest(5)
    void tenSimultaneousSosRetriesCreateOneAlert() throws Exception {
        String hiker = obtainToken(Role.HIKER);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/sos", hiker, SOS_BODY, key));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("sos_alerts")).isEqualTo(1);
    }

    /**
     * Anche senza chiave, dieci SOS simultanei dello stesso escursionista devono creare una sola allerta: in
     * questo caso fa lo stesso lavoro un advisory lock di PostgreSQL preso per escursionista.
     */
    @RepeatedTest(5)
    void tenSimultaneousSosWithoutKeyCreateOneAlert() throws Exception {
        String hiker = obtainToken(Role.HIKER);

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/sos", hiker, SOS_BODY));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("sos_alerts")).isEqualTo(1);
    }

    /** Dieci invii simultanei della stessa prenotazione con la stessa chiave devono creare una sola prenotazione. */
    @RepeatedTest(5)
    void tenSimultaneousBookingRetriesCreateOneBooking() throws Exception {
        String hiker = obtainToken(Role.HIKER);
        String body = bookingBody(insertRefuge(10, createUser(Role.PARK_MANAGER).getId()), 1);
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.POST, "/api/v1/bookings", hiker, body, key));

        assertOneCreatedAndNineReplays(results);
        assertThat(count("bookings")).isEqualTo(1);
    }

    /**
     * Riusare la stessa chiave per una prenotazione diversa (qui con un ospite in più) è un errore del client:
     * si attende 422 con slug {@code idempotency-key-reused}.
     */
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

    /**
     * Un SOS non viene mai rifiutato: se la stessa chiave arriva con un corpo diverso, il server risponde 200
     * con l'allerta già creata invece di dare errore.
     */
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

    /**
     * Controlla che su dieci risposte ci sia una sola creazione (201), nove ripetizioni (200) e un solo id.
     *
     * @param results le risposte delle richieste concorrenti
     */
    private void assertOneCreatedAndNineReplays(List<HttpResult> results) {
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(9);
        assertThat(results.stream().map(this::id).distinct()).hasSize(1);
    }

    /**
     * Costruisce il corpo di una prenotazione di una notte, tra dieci giorni.
     *
     * @param refugeId l'id del rifugio
     * @param guests   il numero di ospiti
     * @return il corpo JSON
     */
    private String bookingBody(UUID refugeId, int guests) {
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        return """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":%d}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1), guests);
    }

    /**
     * Legge l'id della risorsa dal corpo della risposta.
     *
     * @param result la risposta
     * @return il valore del campo {@code id}
     */
    private String id(HttpResult result) {
        return json(result.body()).get("id").asString();
    }

    /**
     * Conta le righe di una tabella.
     *
     * @param table il nome della tabella (sempre una costante del test, mai un input esterno)
     * @return il numero di righe
     */
    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
