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

// La capacità di un rifugio non scende sotto gli ospiti già confermati per una notte futura
class RefugeCapacityIT extends AbstractIntegrationTest {

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

    // Audit 2026-10, piste 4 e 5: check-in al massimo a un anno (niente generate_series enormi sotto il lock del
    // rifugio) e al massimo 5 prenotazioni attive per utente (un account non occupa un rifugio per mesi)
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

    @Test
    void oneUserCannotHoldMoreThanFiveActiveBookings() {
        UUID refugeId = insertRefuge(20, createUser(Role.PARK_MANAGER).getId());
        String hoarder = obtainToken(Role.HIKER);
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);

        for (int i = 0; i < 5; i++) {
            assertThat(book(hoarder, refugeId, checkIn.plusDays(i * 2L), 2).status()).isEqualTo(201);
        }
        assertThat(book(hoarder, refugeId, checkIn.plusDays(20), 2).status()).isEqualTo(422);
        // gli altri continuano a prenotare
        assertThat(book(obtainToken(Role.HIKER), refugeId, checkIn, 2).status()).isEqualTo(201);
    }

    private HttpResult book(String token, UUID refugeId, LocalDate checkIn, int guests) {
        return call(HttpMethod.POST, "/api/v1/bookings", token, """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":%d}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1), guests),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private HttpResult changeCapacity(String token, UUID refugeId, int capacity) {
        return call(HttpMethod.PATCH, "/api/v1/refuges/" + refugeId + "/capacity", token, """
                {"capacity":%d}
                """.formatted(capacity));
    }

    private void insertBooking(UUID refugeId, UUID userId, LocalDate checkIn, LocalDate checkOut, String status) {
        jdbcTemplate.update("""
                INSERT INTO bookings (id, refuge_id, user_id, idempotency_key, check_in, check_out, guests, status)
                VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), ?, ?, 8, ?)
                """, refugeId, userId, checkIn, checkOut, status);
    }
}
