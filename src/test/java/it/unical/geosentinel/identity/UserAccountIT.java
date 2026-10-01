package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Profilo, cambio password, cancellazione dell'account (anonimizzazione + pulizia nelle altre feature)
class UserAccountIT extends AbstractIntegrationTest {

    @Test
    void hikerDeletionAnonymizesAndRevokesEverything() {
        User hiker = createUser(Role.HIKER);
        JsonNode tokens = login(hiker.getEmail(), TEST_PASSWORD);
        String access = tokens.get("accessToken").asString();
        String otherDevice = login(hiker.getEmail(), TEST_PASSWORD).get("accessToken").asString();

        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        LocalDate checkIn = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        HttpResult booking = call(HttpMethod.POST, "/api/v1/bookings", access, """
                {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":2}
                """.formatted(refugeId, checkIn, checkIn.plusDays(2)), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(booking.status()).isEqualTo(201);
        assertThat(call(HttpMethod.POST, "/api/v1/sos", access, "{\"location\":{\"lat\":39.3,\"lon\":16.2}}").status()).isEqualTo(201);

        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", access, null).status()).isEqualTo(204);

        // access token in blacklist, login e refresh chiusi
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", access, null).status()).isEqualTo(401);
        // anche l'access token di un altro dispositivo: niente prenotazioni, SOS o foto dopo la cancellazione
        assertThat(call(HttpMethod.GET, "/api/v1/bookings/my", otherDevice, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(hiker.getEmail(), TEST_PASSWORD)).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString())).status()).isEqualTo(401);

        // pulizia nelle altre feature e anonimizzazione
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE user_id = ?", String.class, hiker.getId()))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sos_alerts WHERE hiker_id = ?", String.class, hiker.getId()))
                .isEqualTo("CANCELLED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT email, display_name, enabled, deleted_at FROM users WHERE id = ?", hiker.getId());
        assertThat(row.get("email")).isEqualTo("deleted-" + hiker.getId() + "@invalid");
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();

        // l'email originale è di nuovo libera
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s","password":"%s","displayName":"Di nuovo","birthDate":"1990-01-01"}
                """.formatted(hiker.getEmail(), TEST_PASSWORD)).status()).isEqualTo(201);
    }

    @Test
    void staffAccountCannotBeSelfDeleted() {
        String token = obtainToken(Role.PARK_MANAGER);

        HttpResult result = call(HttpMethod.DELETE, "/api/v1/users/me", token, null);

        assertThat(result.status()).isEqualTo(409);
        assertThat(json(result.body()).get("type").asString()).endsWith("/account-managed");
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", token, null).status()).isEqualTo(200);
    }

    @Test
    void passwordChangeRevokesOtherSessionsAndReturnsNewTokens() throws InterruptedException {
        User hiker = createUser(Role.HIKER);
        JsonNode oldSession = login(hiker.getEmail(), TEST_PASSWORD);
        String access = oldSession.get("accessToken").asString();
        String otherDevice = login(hiker.getEmail(), TEST_PASSWORD).get("accessToken").asString();
        String newPassword = "Un'altra-Password-456";
        // il cambio password revoca i token emessi fino al secondo precedente (iat ha la precisione del secondo)
        Thread.sleep(1_100);

        // password nel %s, non letterale nel JSON: gitleaks (generic-api-key) scambia "currentPassword":"<valore>" per un segreto
        HttpResult wrong = call(HttpMethod.POST, "/api/v1/users/me/password", access, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted("wrong-" + newPassword, newPassword));
        // 422 e non 401: l'app non deve credere che il token sia scaduto
        assertThat(wrong.status()).isEqualTo(422);

        HttpResult changed = call(HttpMethod.POST, "/api/v1/users/me/password", access, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(TEST_PASSWORD, newPassword));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(json(changed.body()).get("refreshToken").asString()).isNotBlank();
        // gli access token delle altre sessioni non valgono più, quello nuovo sì
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", otherDevice, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", json(changed.body()).get("accessToken").asString(), null)
                .status()).isEqualTo(200);

        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(oldSession.get("refreshToken").asString())).status()).isEqualTo(401);
        assertThat(login(hiker.getEmail(), newPassword).get("accessToken").asString()).isNotBlank();
    }

    @Test
    void currentPasswordGuessesHitThePerAccountLimit() {
        String token = obtainToken(Role.HIKER);
        String body = """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted("wrong-" + TEST_PASSWORD, "Nuova-" + TEST_PASSWORD);

        // stesso limite per account del login (10 tentativi ogni 15 minuti): con un token rubato non si indovina la password
        HttpResult last = null;
        for (int i = 0; i < 15; i++) {
            last = call(HttpMethod.POST, "/api/v1/users/me/password", token, body);
            if (last.status() != 422) {
                break;
            }
        }
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.headers().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void profileCanBeReadAndUpdated() {
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);

        JsonNode profile = json(call(HttpMethod.GET, "/api/v1/users/me", token, null).body());
        assertThat(profile.get("email").asString()).isEqualTo(hiker.getEmail());
        assertThat(profile.get("role").asString()).isEqualTo("HIKER");

        HttpResult updated = call(HttpMethod.PATCH, "/api/v1/users/me", token, "{\"displayName\":\"Nuovo nome\"}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(json(updated.body()).get("displayName").asString()).isEqualTo("Nuovo nome");
    }

    private JsonNode login(String email, String password) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password));
        assertThat(result.status()).isEqualTo(200);
        return json(result.body());
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }
}
