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

// Nessun overbooking: il lock pessimistico sulla riga del rifugio serializza le prenotazioni.
// Ripetuto 5 volte: se passasse solo a volte, ci sarebbe una race
class RefugeBookingConcurrencyIT extends AbstractIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int REQUESTS = 50;

    @Autowired
    private BookingRepository bookingRepository;

    @RepeatedTest(5)
    void fiftyConcurrentBookingsOnTenBedsGiveExactlyTenSuccesses() throws Exception {
        UUID refugeId = insertRefuge(CAPACITY, createUser(Role.PARK_MANAGER).getId());
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        String body = """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1));
        // un utente per richiesta, ognuno con la sua Idempotency-Key: la stessa chiave dello stesso utente
        // sarebbe un retry (IdempotencyIT), non una nuova prenotazione
        List<String> tokens = IntStream.range(0, REQUESTS).mapToObj(i -> obtainToken(Role.HIKER)).toList();

        List<Integer> statuses = runConcurrently(REQUESTS, i -> call(HttpMethod.POST, "/api/v1/bookings", tokens.get(i),
                body, Map.of("Idempotency-Key", UUID.randomUUID().toString())).status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(CAPACITY);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(REQUESTS - CAPACITY);
        assertThat(bookingRepository.maxNightlyOccupancy(refugeId, checkIn, checkIn.plusDays(1))).isEqualTo(CAPACITY);
    }
}
