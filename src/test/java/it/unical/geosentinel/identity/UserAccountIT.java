// Test di integrazione della gestione del proprio account, con chiamate HTTP vere e PostGIS e Redis in
// Testcontainers: lettura e modifica del profilo, cambio password e cancellazione dell'account. La
// cancellazione anonimizza l'utente, revoca tutte le sue sessioni e fa pulizia nelle altre funzionalità
// (prenotazioni e SOS).
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

/**
 * Verifica le operazioni dell'utente sul proprio account sotto {@code /users/me}.
 */
class UserAccountIT extends AbstractIntegrationTest {

    /**
     * Un escursionista con due sessioni, una prenotazione e un SOS attivo cancella il proprio account. Il
     * risultato atteso è: 204; access token di entrambi i dispositivi, login e refresh rifiutati con 401;
     * prenotazione e SOS annullati; utente anonimizzato e disattivato; email originale di nuovo disponibile
     * per una nuova registrazione.
     */
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

        // L'access token usato è nella blacklist; login e refresh non funzionano più.
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", access, null).status()).isEqualTo(401);
        // Anche l'access token dell'altro dispositivo è rifiutato: dopo la cancellazione non si possono più
        // fare prenotazioni, SOS o caricare foto con quell'account.
        assertThat(call(HttpMethod.GET, "/api/v1/bookings/my", otherDevice, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(hiker.getEmail(), TEST_PASSWORD)).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString())).status()).isEqualTo(401);

        // Pulizia nelle altre funzionalità e anonimizzazione dei dati personali.
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE user_id = ?", String.class, hiker.getId()))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sos_alerts WHERE hiker_id = ?", String.class, hiker.getId()))
                .isEqualTo("CANCELLED");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT email, display_name, enabled, deleted_at FROM users WHERE id = ?", hiker.getId());
        assertThat(row.get("email")).isEqualTo("deleted-" + hiker.getId() + "@invalid");
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();

        // L'email originale si può usare di nuovo per registrarsi.
        assertThat(call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s","password":"%s","displayName":"Di nuovo","birthDate":"1990-01-01"}
                """.formatted(hiker.getEmail(), TEST_PASSWORD)).status()).isEqualTo(201);
    }

    /**
     * Un account del personale non può cancellarsi da solo: si attende 409 {@code account-managed} e l'account
     * deve restare utilizzabile.
     */
    @Test
    void staffAccountCannotBeSelfDeleted() {
        String token = obtainToken(Role.PARK_MANAGER);

        HttpResult result = call(HttpMethod.DELETE, "/api/v1/users/me", token, null);

        assertThat(result.status()).isEqualTo(409);
        assertThat(json(result.body()).get("type").asString()).endsWith("/account-managed");
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", token, null).status()).isEqualTo(200);
    }

    /**
     * Con la password attuale sbagliata il cambio dà 422. Con quella giusta dà 200 e restituisce token nuovi:
     * gli access token e i refresh token delle altre sessioni smettono di funzionare, quello nuovo funziona e
     * si può fare login con la nuova password.
     */
    @Test
    void passwordChangeRevokesOtherSessionsAndReturnsNewTokens() throws InterruptedException {
        User hiker = createUser(Role.HIKER);
        JsonNode oldSession = login(hiker.getEmail(), TEST_PASSWORD);
        String access = oldSession.get("accessToken").asString();
        String otherDevice = login(hiker.getEmail(), TEST_PASSWORD).get("accessToken").asString();
        String newPassword = "Un'altra-Password-456";
        // Il cambio password revoca i token emessi fino al secondo precedente, perché il claim iat ha la
        // precisione del secondo: si aspetta più di un secondo per separare i vecchi token dai nuovi.
        Thread.sleep(1_100);

        // La password è inserita con %s e non scritta direttamente nel JSON: lo scanner dei segreti (gitleaks,
        // regola generic-api-key) scambierebbe "currentPassword":"valore" per una credenziale.
        HttpResult wrong = call(HttpMethod.POST, "/api/v1/users/me/password", access, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted("wrong-" + newPassword, newPassword));
        // 422 e non 401: con un 401 l'app crederebbe che il token sia scaduto e chiuderebbe la sessione.
        assertThat(wrong.status()).isEqualTo(422);

        HttpResult changed = call(HttpMethod.POST, "/api/v1/users/me/password", access, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(TEST_PASSWORD, newPassword));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(json(changed.body()).get("refreshToken").asString()).isNotBlank();
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", otherDevice, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", json(changed.body()).get("accessToken").asString(), null)
                .status()).isEqualTo(200);

        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(oldSession.get("refreshToken").asString())).status()).isEqualTo(401);
        assertThat(login(hiker.getEmail(), newPassword).get("accessToken").asString()).isNotBlank();
    }

    /**
     * I tentativi sbagliati di indicare la password attuale contano nello stesso limite per account del login
     * (10 ogni 15 minuti). Così chi ha rubato un access token non può indovinare la password: dopo una serie di
     * 422 si attende un 429 con Retry-After.
     */
    @Test
    void currentPasswordGuessesHitThePerAccountLimit() {
        String token = obtainToken(Role.HIKER);
        String body = """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted("wrong-" + TEST_PASSWORD, "Nuova-" + TEST_PASSWORD);

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

    /** Il profilo riporta email e ruolo dell'utente, e il nome visualizzato si può modificare. */
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

    /**
     * Esegue il login e controlla che riesca.
     *
     * @param email    l'email
     * @param password la password
     * @return il corpo della risposta, con access token e refresh token
     */
    private JsonNode login(String email, String password) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password));
        assertThat(result.status()).isEqualTo(200);
        return json(result.body());
    }

    /**
     * Costruisce il corpo JSON di una richiesta di login.
     *
     * @param email    l'email
     * @param password la password
     * @return il corpo JSON
     */
    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }
}
