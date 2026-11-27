// Test di integrazione del prezzo dei rifugi (M10), con chiamate HTTP vere e PostGIS e Redis in Testcontainers.
// Il prezzo è a persona per notte, in centesimi; la prenotazione ne copia il totale (prezzo × notti × ospiti) e
// lo conserva anche se il gestore cambia il prezzo dopo. Lo imposta solo il gestore del rifugio o un ADMIN.
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
 * Verifica l'API del prezzo dei rifugi e il totale copiato sulle prenotazioni.
 */
class RefugePriceIT extends AbstractIntegrationTest {

    /** Data di arrivo delle prenotazioni del test, nel futuro. */
    private final LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);

    /**
     * Con 35 € a notte, 3 notti e 2 ospiti il totale è 210 €, sia nella disponibilità sia sulla prenotazione;
     * dopo un aumento del prezzo la prenotazione mostra ancora 210 €, la disponibilità il prezzo nuovo.
     */
    @Test
    void bookingCopiesTheTotalAndKeepsItAfterAPriceChange() {
        User manager = createUser(Role.PARK_MANAGER);
        UUID refugeId = insertRefuge(10, manager.getId());
        HttpResult priced = changePrice(tokenFor(manager), refugeId, "3500");
        assertThat(priced.status()).isEqualTo(200);
        assertThat(json(priced.body()).get("pricePerNightCents").asInt()).isEqualTo(3500);

        String hiker = obtainToken(Role.HIKER);
        assertThat(availabilityTotal(hiker, refugeId, 2)).isEqualTo("21000");

        HttpResult booking = book(hiker, refugeId, 3, 2);
        assertThat(booking.status()).isEqualTo(201);
        String bookingId = json(booking.body()).get("id").asString();
        assertThat(json(booking.body()).get("totalPriceCents").asInt()).isEqualTo(21000);

        assertThat(changePrice(tokenFor(manager), refugeId, "5000").status()).isEqualTo(200);
        HttpResult reread = call(HttpMethod.GET, "/api/v1/bookings/" + bookingId, hiker, null);
        assertThat(json(reread.body()).get("totalPriceCents").asInt()).isEqualTo(21000);
        assertThat(availabilityTotal(hiker, refugeId, 2)).isEqualTo("30000");
    }

    /** Un rifugio senza prezzo dà un totale {@code null} sia nella disponibilità sia sulla prenotazione. */
    @Test
    void refugeWithoutPriceGivesNullTotals() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        String hiker = obtainToken(Role.HIKER);

        assertThat(availabilityTotal(hiker, refugeId, 1)).isEqualTo("null");
        HttpResult booking = book(hiker, refugeId, 1, 1);
        assertThat(booking.status()).isEqualTo(201);
        assertThat(json(booking.body()).get("totalPriceCents").isNull()).isTrue();
    }

    /** Un prezzo negativo o oltre 1000 € dà 400 {@code validation-error}; {@code null} toglie il prezzo. */
    @Test
    void invalidPriceIsRejectedAndNullClearsIt() {
        User manager = createUser(Role.PARK_MANAGER);
        UUID refugeId = insertRefuge(10, manager.getId());
        String token = tokenFor(manager);

        HttpResult negative = changePrice(token, refugeId, "-1");
        assertThat(negative.status()).isEqualTo(400);
        assertThat(isProblemJson(negative)).isTrue();
        assertThat(json(negative.body()).get("type").asString()).endsWith("/validation-error");
        assertThat(changePrice(token, refugeId, "100001").status()).isEqualTo(400);
        // Un decimale non deve diventare un intero troncato in silenzio (35.99 → 35): gli importi sono solo centesimi interi.
        assertThat(changePrice(token, refugeId, "35.99").status()).isEqualTo(400);

        assertThat(changePrice(token, refugeId, "100000").status()).isEqualTo(200);
        HttpResult cleared = changePrice(token, refugeId, "null");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(json(cleared.body()).get("pricePerNightCents").isNull()).isTrue();
    }

    /**
     * Il prezzo lo cambia solo il gestore di quel rifugio o un ADMIN: un altro gestore riceve 403, un
     * escursionista 403, un ADMIN 200.
     */
    @Test
    void onlyTheManagerOrAnAdminCanChangeThePrice() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());

        assertThat(changePrice(obtainToken(Role.PARK_MANAGER), refugeId, "3500").status()).isEqualTo(403);
        assertThat(changePrice(obtainToken(Role.HIKER), refugeId, "1").status()).isEqualTo(403);
        assertThat(changePrice(obtainToken(Role.ADMIN), refugeId, "3500").status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT price_per_night_cents FROM refuges WHERE id = ?",
                Integer.class, refugeId)).isEqualTo(3500);
    }

    /** Il client non può imporre un importo: un {@code totalPriceCents} nel body della prenotazione è ignorato. */
    @Test
    void clientCannotSendItsOwnTotal() {
        User manager = createUser(Role.PARK_MANAGER);
        UUID refugeId = insertRefuge(10, manager.getId());
        changePrice(tokenFor(manager), refugeId, "3500");

        HttpResult booking = call(HttpMethod.POST, "/api/v1/bookings", obtainToken(Role.HIKER), """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":1,"totalPriceCents":1}
                """.formatted(refugeId, checkIn, checkIn.plusDays(1)), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        // Se il body con un campo sconosciuto venisse rifiutato, andrebbe bene lo stesso: l'importante è che 1 non passi.
        if (booking.status() == 201) {
            assertThat(json(booking.body()).get("totalPriceCents").asInt()).isEqualTo(3500);
        } else {
            assertThat(booking.status()).isEqualTo(400);
        }
    }

    /** {@code guests} della disponibilità segue i limiti di una prenotazione: 0 e 21 danno 400. */
    @Test
    void availabilityGuestsAreBounded() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        String hiker = obtainToken(Role.HIKER);
        assertThat(availability(hiker, refugeId, 0).status()).isEqualTo(400);
        assertThat(availability(hiker, refugeId, 21).status()).isEqualTo(400);
        assertThat(json(call(HttpMethod.GET, "/api/v1/refuges/%s/availability?from=%s&to=%s"
                .formatted(refugeId, checkIn, checkIn.plusDays(1)), hiker, null).body()).get("guests").asInt()).isEqualTo(1);
    }

    private HttpResult changePrice(String token, UUID refugeId, String price) {
        return call(HttpMethod.PATCH, "/api/v1/refuges/" + refugeId + "/price", token, """
                {"pricePerNightCents":%s}
                """.formatted(price));
    }

    private HttpResult book(String token, UUID refugeId, int nights, int guests) {
        return call(HttpMethod.POST, "/api/v1/bookings", token, """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":%d}
                """.formatted(refugeId, checkIn, checkIn.plusDays(nights), guests),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private HttpResult availability(String token, UUID refugeId, int guests) {
        return call(HttpMethod.GET, "/api/v1/refuges/%s/availability?from=%s&to=%s&guests=%d"
                .formatted(refugeId, checkIn, checkIn.plusDays(3), guests), token, null);
    }

    /** Totale della disponibilità per 3 notti, come testo ("null" se manca). */
    private String availabilityTotal(String token, UUID refugeId, int guests) {
        HttpResult result = availability(token, refugeId, guests);
        assertThat(result.status()).isEqualTo(200);
        return json(result.body()).get("totalPriceCents").toString();
    }
}
