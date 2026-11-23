// Test di integrazione dei limiti sui posti letto e sulle prenotazioni dei rifugi, con chiamate HTTP vere e
// PostGIS e Redis in Testcontainers. La capacità di un rifugio non può scendere sotto gli ospiti già
// confermati per una notte futura e ha un massimo; le prenotazioni hanno limiti di anticipo e di numero per
// utente, che impediscono a un solo account di bloccare un rifugio o di appesantire il database.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica le regole sulla modifica della capacità di un rifugio e sui limiti delle prenotazioni.
 */
class RefugeCapacityIT extends AbstractIntegrationTest {

    /**
     * Con 6 ospiti confermati per una notte futura, il gestore non può ridurre i posti a 5 (409
     * {@code capacity-exceeded} in formato ProblemDetail), ma può ridurli a 6.
     */
    @Test
    void capacityCannotDropBelowConfirmedGuests() {
        User manager = createUser(Role.PARK_MANAGER);
        String managerToken = tokenFor(manager);
        UUID refugeId = insertRefuge(10, manager.getId());

        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        HttpResult booking = call(HttpMethod.POST, "/api/v1/bookings", obtainToken(Role.HIKER), """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":6}
                """.formatted(refugeId, checkIn, checkIn.plusDays(2)), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(booking.status()).isEqualTo(201);

        HttpResult tooLow = changeCapacity(managerToken, refugeId, 5);
        assertThat(tooLow.status()).isEqualTo(409);
        assertThat(isProblemJson(tooLow)).isTrue();
        assertThat(json(tooLow.body()).get("type").asString()).endsWith("/capacity-exceeded");

        assertThat(changeCapacity(managerToken, refugeId, 6).status()).isEqualTo(200);
    }

    /**
     * La capacità ha un massimo: un valore enorme deve dare 400 {@code validation-error}, mentre 1000 è ammesso.
     */
    @Test
    void capacityHasAnUpperBound() {
        User manager = createUser(Role.PARK_MANAGER);
        UUID refugeId = insertRefuge(10, manager.getId());

        // Senza un massimo, un valore enorme farebbe sembrare il rifugio sempre libero.
        HttpResult huge = changeCapacity(tokenFor(manager), refugeId, Integer.MAX_VALUE);
        assertThat(huge.status()).isEqualTo(400);
        assertThat(json(huge.body()).get("type").asString()).endsWith("/validation-error");

        assertThat(changeCapacity(tokenFor(manager), refugeId, 1000).status()).isEqualTo(200);
    }

    /**
     * Le prenotazioni già passate e quelle annullate non contano nel calcolo: con 8 ospiti in entrambe, la
     * capacità si può comunque ridurre a 1.
     */
    @Test
    void cancelledAndPastBookingsDoNotCount() {
        User manager = createUser(Role.PARK_MANAGER);
        UUID refugeId = insertRefuge(10, manager.getId());
        User hiker = createUser(Role.HIKER);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        insertBooking(refugeId, hiker.getId(), today.minusDays(5), today.minusDays(3), "CONFIRMED");
        insertBooking(refugeId, hiker.getId(), today.plusDays(3), today.plusDays(5), "CANCELLED");

        assertThat(changeCapacity(tokenFor(manager), refugeId, 1).status()).isEqualTo(200);
    }

    /**
     * L'arrivo può essere al massimo un anno dopo oggi: a 366 giorni la prenotazione deve essere rifiutata con
     * 422, a 365 accettata. Il limite evita che il calcolo dell'occupazione notte per notte, eseguito sotto il
     * lock del rifugio, debba generare serie di date molto lunghe.
     */
    @Test
    void checkInTooFarAheadIsRejected() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        String hiker = obtainToken(Role.HIKER);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        HttpResult farAhead = book(hiker, refugeId, today.plusDays(366), 1);
        assertThat(farAhead.status()).isEqualTo(422);
        assertThat(isProblemJson(farAhead)).isTrue();
        assertThat(book(hiker, refugeId, today.plusDays(365), 1).status()).isEqualTo(201);
    }

    /**
     * Un utente può avere al massimo 5 prenotazioni attive: la sesta deve dare 422, così un solo account non
     * può occupare un rifugio per mesi. Gli altri utenti devono poter continuare a prenotare.
     */
    @Test
    void oneUserCannotHoldMoreThanFiveActiveBookings() {
        UUID refugeId = insertRefuge(20, createUser(Role.PARK_MANAGER).getId());
        String hoarder = obtainToken(Role.HIKER);
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);

        for (int i = 0; i < 5; i++) {
            assertThat(book(hoarder, refugeId, checkIn.plusDays(i * 2L), 2).status()).isEqualTo(201);
        }
        assertThat(book(hoarder, refugeId, checkIn.plusDays(20), 2).status()).isEqualTo(422);
        assertThat(book(obtainToken(Role.HIKER), refugeId, checkIn, 2).status()).isEqualTo(201);
    }

    /**
     * Invia una prenotazione di una notte con una Idempotency-Key nuova.
     *
     * @param token    l'access token dell'escursionista
     * @param refugeId l'id del rifugio
     * @param checkIn  la data di arrivo
     * @param guests   il numero di ospiti
     * @return la risposta del server
     */
    private HttpResult book(String token, UUID refugeId, LocalDate checkIn, int guests) {
        return call(HttpMethod.POST, "/api/v1/bookings", token, """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":%d}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1), guests),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    /**
     * Chiede di cambiare i posti letto di un rifugio.
     *
     * @param token    l'access token di chi fa la richiesta
     * @param refugeId l'id del rifugio
     * @param capacity la nuova capacità
     * @return la risposta del server
     */
    private HttpResult changeCapacity(String token, UUID refugeId, int capacity) {
        return call(HttpMethod.PATCH, "/api/v1/refuges/" + refugeId + "/capacity", token, """
                {"capacity":%d}
                """.formatted(capacity));
    }

    /**
     * Inserisce direttamente nel database una prenotazione da 8 ospiti, anche con date passate, cosa che le API
     * non permetterebbero.
     *
     * @param refugeId l'id del rifugio
     * @param userId   l'id dell'utente
     * @param checkIn  la data di arrivo
     * @param checkOut la data di partenza
     * @param status   lo stato della prenotazione, ad esempio {@code CONFIRMED} o {@code CANCELLED}
     */
    private void insertBooking(UUID refugeId, UUID userId, LocalDate checkIn, LocalDate checkOut, String status) {
        jdbcTemplate.update("""
                INSERT INTO bookings (id, refuge_id, user_id, idempotency_key, check_in, check_out, guests, status)
                VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), ?, ?, 8, ?)
                """, refugeId, userId, checkIn, checkOut, status);
    }
}
