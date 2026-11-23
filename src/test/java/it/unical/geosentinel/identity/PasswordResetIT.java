// Test di integrazione del reset della password tramite codice via email, con chiamate HTTP vere e PostGIS e
// Redis in Testcontainers: richiesta, conferma, chiusura di tutte le sessioni, conteggio dei tentativi,
// scadenza, rate limit e pulizia dei codici. Il codice vero viene generato in background e "spedito" da
// LoggingPasswordResetMailer, che nei test si limita a scrivere nel log. Per provare l'intero flusso HTTP
// senza leggere la posta, il test sostituisce nel database l'hash del codice con quello di un codice noto.
package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.application.PasswordResetCleanupJob;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il flusso completo di reset della password e le sue protezioni contro gli abusi.
 */
class PasswordResetIT extends AbstractIntegrationTest {

    /** Codice di reset noto, il cui hash viene scritto al posto di quello del codice generato. */
    private static final String KNOWN_CODE = "ABCDEFGH23456789";

    /** Nuova password valida usata nelle conferme. */
    private static final String NEW_PASSWORD = "NuovaPassword456!";

    /** Encoder delle password, usato per calcolare l'hash del codice noto. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Job che cancella i codici scaduti; nei test i job pianificati sono spenti e lo si chiama a mano. */
    @Autowired
    private PasswordResetCleanupJob cleanupJob;

    /** Accesso diretto a Redis, per controllare il contatore dei tentativi della verifica in due passaggi. */
    @Autowired
    private StringRedisTemplate redis;

    /**
     * Flusso completo: l'utente chiede il codice e lo conferma con una nuova password. Il risultato atteso è
     * 204, la vecchia password non funziona più e la nuova sì, l'access token e il refresh token precedenti
     * sono rifiutati, il contatore dei tentativi della verifica in due passaggi viene azzerato, e il codice non
     * si può usare una seconda volta (422 {@code invalid-reset-code}).
     */
    @Test
    void fullFlowChangesPasswordAndClosesEverySession() {
        User hiker = createUser(Role.HIKER);
        JsonNode tokens = login(hiker.getEmail(), TEST_PASSWORD);
        String access = tokens.get("accessToken").asString();

        requestAndAwait(hiker);
        useKnownCode(hiker.getId());
        // Simula un bucket della verifica in due passaggi esaurito da chi conosceva la vecchia password:
        // dopo il reset il proprietario deve poter usare di nuovo il codice a due fattori.
        redis.opsForValue().set("rl:mfa:" + hiker.getId(), "0");

        // Il codice è scritto come lo digiterebbe l'utente, in minuscolo e con il trattino.
        assertThat(confirm(hiker.getEmail(), "abcdefgh-23456789", NEW_PASSWORD).status()).isEqualTo(204);
        assertThat(redis.hasKey("rl:mfa:" + hiker.getId())).isFalse();

        assertThat(loginStatus(hiker.getEmail(), TEST_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus(hiker.getEmail(), NEW_PASSWORD)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", access, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString())).status()).isEqualTo(401);

        // Il codice vale una sola volta.
        HttpResult reuse = confirm(hiker.getEmail(), KNOWN_CODE, TEST_PASSWORD);
        assertThat(reuse.status()).isEqualTo(422);
        assertThat(isProblemJson(reuse)).isTrue();
        assertThat(json(reuse.body()).get("type").asString()).endsWith("/invalid-reset-code");
    }

    /**
     * Una richiesta per un'email non registrata risponde 204 come per una registrata, ma non crea nessun
     * codice; una conferma per quell'email dà lo stesso 422 di un codice sbagliato. Così dalle risposte non
     * si capisce quali email sono iscritte.
     */
    @Test
    void unknownEmailLooksLikeAKnownOne() {
        assertThat(request("nessuno@geosentinel.dev").status()).isEqualTo(204);
        // L'executor che genera i codici ha un solo thread: quando il codice dell'utente esistente compare, la
        // richiesta per l'email sconosciuta è già stata elaborata.
        User known = createUser(Role.HIKER);
        requestAndAwait(known);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM password_reset_tokens", UUID.class))
                .containsExactly(known.getId());
        assertThat(confirm("nessuno@geosentinel.dev", KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
    }

    /**
     * Cinque codici sbagliati danno 422 e vengono contati; al quinto il codice è consumato e anche quello
     * giusto viene rifiutato. La password resta quella vecchia.
     */
    @Test
    void wrongCodesAreCountedAndBurnTheCode() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);
        useKnownCode(hiker.getId());

        for (int i = 0; i < 5; i++) {
            assertThat(confirm(hiker.getEmail(), "ZZZZZZZZZZZZZZZZ", NEW_PASSWORD).status()).isEqualTo(422);
        }
        // I tentativi falliti restano salvati anche se la risposta è un errore, perché la transazione non viene
        // annullata per quell'eccezione (noRollbackFor).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM password_reset_tokens WHERE user_id = ?", Integer.class, hiker.getId())).isEqualTo(5);
        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(loginStatus(hiker.getEmail(), TEST_PASSWORD)).isEqualTo(200);
    }

    /**
     * Un codice troppo lungo in byte o troppo corto deve dare 422 e contare come tentativo, senza mai
     * provocare un errore 500.
     */
    @Test
    void malformedOrOversizedCodeIs422NotA500() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);

        // 32 caratteri da 3 byte ciascuno fanno 96 byte, oltre il limite di 72 byte di BCrypt.
        assertThat(confirm(hiker.getEmail(), "€".repeat(32), NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(confirm(hiker.getEmail(), "short", NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM password_reset_tokens WHERE user_id = ?", Integer.class, hiker.getId())).isEqualTo(2);
    }

    /** Una nuova richiesta invalida il codice precedente: ne resta uno solo ancora utilizzabile. */
    @Test
    void newRequestInvalidatesThePreviousCode() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);
        useKnownCode(hiker.getId());
        requestAndAwait(hiker);

        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ? AND consumed_at IS NULL",
                Integer.class, hiker.getId())).isEqualTo(1);
    }

    /** Un codice scaduto deve essere rifiutato con 422 anche se è quello giusto. */
    @Test
    void expiredCodeIsRejected() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);
        useKnownCode(hiker.getId());
        jdbcTemplate.update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?",
                hiker.getId());

        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
    }

    /**
     * Dopo tre richieste per la stessa email (anche scritta in maiuscolo) la quarta deve dare 429 con
     * Retry-After. Lo stesso limite vale per un'email inesistente, così il 429 non rivela se l'account esiste.
     */
    @Test
    void fourthRequestForTheSameEmailIsLimited() {
        User hiker = createUser(Role.HIKER);
        for (int i = 0; i < 3; i++) {
            requestAndAwait(hiker);
        }
        HttpResult limited = request(hiker.getEmail().toUpperCase());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.headers().getFirst("Retry-After")).isNotNull();
        for (int i = 0; i < 3; i++) {
            request("ghost@geosentinel.dev");
        }
        assertThat(request("ghost@geosentinel.dev").status()).isEqualTo(429);
    }

    /** Una nuova password che non rispetta le regole (qui troppo corta) deve dare 400. */
    @Test
    void weakNewPasswordIs400() {
        User hiker = createUser(Role.HIKER);
        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, "corta").status()).isEqualTo(400);
    }

    /**
     * La cancellazione dell'account elimina i suoi codici, e il job di pulizia elimina quelli scaduti: alla
     * fine deve restare solo il codice ancora valido dell'utente non coinvolto.
     */
    @Test
    void accountDeletionAndCleanupRemoveCodes() {
        User expired = createUser(Role.HIKER);
        requestAndAwait(expired);
        jdbcTemplate.update("UPDATE password_reset_tokens SET expires_at = now() - interval '2 days' WHERE user_id = ?",
                expired.getId());
        User deleted = createUser(Role.HIKER);
        requestAndAwait(deleted);
        User kept = createUser(Role.HIKER);
        requestAndAwait(kept);

        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", tokenFor(deleted), null).status()).isEqualTo(204);
        cleanupJob.deleteExpiredCode();

        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM password_reset_tokens", UUID.class))
                .containsExactly(kept.getId());
    }

    /**
     * Un account bloccato dal limite sui login sbagliati (429) torna utilizzabile dopo un reset riuscito: il
     * login con la nuova password deve dare 200.
     */
    @Test
    void resetUnlocksAnAccountBlockedByWrongLogins() {
        User hiker = createUser(Role.HIKER);
        int status;
        do {
            status = loginStatus(hiker.getEmail(), "sbagliata-" + UUID.randomUUID());
        } while (status == 401);
        assertThat(status).isEqualTo(429);

        requestAndAwait(hiker);
        useKnownCode(hiker.getId());
        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(204);

        assertThat(loginStatus(hiker.getEmail(), NEW_PASSWORD)).isEqualTo(200);
    }

    /**
     * Invia la richiesta di reset e aspetta che il listener asincrono abbia salvato il nuovo codice.
     *
     * @param user l'utente che chiede il reset
     */
    private void requestAndAwait(User user) {
        int before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, user.getId());
        assertThat(request(user.getEmail()).status()).isEqualTo(204);
        awaitTokens(user.getId(), before + 1);
    }

    /**
     * Aspetta, per al massimo 10 secondi, che l'utente abbia almeno il numero indicato di codici salvati.
     *
     * @param userId   l'id dell'utente
     * @param expected il numero minimo di codici atteso
     * @throws IllegalStateException se il thread viene interrotto durante l'attesa
     */
    private void awaitTokens(UUID userId, int expected) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, userId) < expected) {
            assertThat(System.nanoTime()).as("reset code not issued in time").isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Sostituisce l'hash del codice ancora valido dell'utente con quello di {@link #KNOWN_CODE}.
     *
     * @param userId l'id dell'utente
     */
    private void useKnownCode(UUID userId) {
        jdbcTemplate.update("UPDATE password_reset_tokens SET code_hash = ? WHERE user_id = ? AND consumed_at IS NULL",
                passwordEncoder.encode(KNOWN_CODE), userId);
    }

    /**
     * Chiede un codice di reset per l'email indicata.
     *
     * @param email l'email
     * @return la risposta del server
     */
    private HttpResult request(String email) {
        return call(HttpMethod.POST, "/api/v1/auth/password-reset/request", null, "{\"email\":\"%s\"}".formatted(email));
    }

    /**
     * Conferma il reset con un codice e una nuova password.
     *
     * @param email       l'email dell'account
     * @param code        il codice ricevuto
     * @param newPassword la nuova password
     * @return la risposta del server
     */
    private HttpResult confirm(String email, String code, String newPassword) {
        return call(HttpMethod.POST, "/api/v1/auth/password-reset/confirm", null,
                "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}".formatted(email, code, newPassword));
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
     * Tenta un login e restituisce solo il codice di stato.
     *
     * @param email    l'email
     * @param password la password
     * @return lo stato HTTP della risposta
     */
    private int loginStatus(String email, String password) {
        return call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password)).status();
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
