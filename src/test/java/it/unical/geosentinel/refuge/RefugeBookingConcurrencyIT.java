// Test di integrazione della concorrenza sulle prenotazioni dei rifugi, con PostGIS e Redis avviati da
// Testcontainers e chiamate HTTP vere verso l'applicazione. Molte richieste in parallelo sullo stesso
// rifugio non devono mai superare i posti disponibili (niente overbooking): il servizio prende un lock
// pessimistico sulla riga del rifugio e così serializza le prenotazioni.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.refuge.infrastructure.persistence.BookingRepository;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che 50 prenotazioni concorrenti su un rifugio da 10 posti producano esattamente 10 successi.
 * Il test viene ripetuto più volte: una race condition si manifesterebbe solo in alcune esecuzioni.
 */
class RefugeBookingConcurrencyIT extends AbstractIntegrationTest {

    /** Posti letto del rifugio di prova. */
    private static final int CAPACITY = 10;

    /** Numero di richieste lanciate in parallelo, ognuna per un posto. */
    private static final int REQUESTS = 50;

    /** Repository usato per leggere l'occupazione massima per notte dopo le richieste. */
    @Autowired
    private BookingRepository bookingRepository;

    /**
     * Cinquanta escursionisti diversi prenotano lo stesso posto per la stessa notte nello stesso momento.
     * Il risultato atteso è: 10 risposte 201, 40 risposte 409 per posti esauriti, e un'occupazione massima
     * registrata nel database pari alla capacità.
     */
    @RepeatedTest(5)
    void fiftyConcurrentBookingsOnTenBedsGiveExactlyTenSuccesses() throws Exception {
        UUID refugeId = insertRefuge(CAPACITY, createUser(Role.PARK_MANAGER).getId());
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        String body = """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1));
        // Ogni richiesta ha un utente diverso e una Idempotency-Key propria: la stessa chiave usata dallo
        // stesso utente verrebbe trattata come un nuovo tentativo della stessa prenotazione (vedi
        // IdempotencyIT) e non come una prenotazione nuova.
        List<String> tokens = IntStream.range(0, REQUESTS).mapToObj(i -> obtainToken(Role.HIKER)).toList();

        List<Integer> statuses = runConcurrently(REQUESTS, i -> call(HttpMethod.POST, "/api/v1/bookings", tokens.get(i),
                body, Map.of("Idempotency-Key", UUID.randomUUID().toString())).status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(CAPACITY);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(REQUESTS - CAPACITY);
        assertThat(bookingRepository.maxNightlyOccupancy(refugeId, checkIn, checkIn.plusDays(1))).isEqualTo(CAPACITY);
    }
}
