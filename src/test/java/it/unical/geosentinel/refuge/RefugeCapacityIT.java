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

// M7.7: la capacità di un rifugio non scende sotto gli ospiti già confermati per una notte futura
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
