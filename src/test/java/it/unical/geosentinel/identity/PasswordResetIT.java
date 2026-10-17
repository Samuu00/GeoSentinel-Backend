package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.application.PasswordResetCleanupJob;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Reset della password: richiesta, conferma, sessioni chiuse, tentativi, scadenza, limite, pulizia.
// Il codice vero parte per email (nei test LoggingPasswordResetMailer): qui si sostituisce l'hash con quello di un
// codice noto, così il flusso HTTP si prova per intero senza leggere la posta
class PasswordResetIT extends AbstractIntegrationTest {
    private static final String KNOWN_CODE = "ABCDEFGH23456789";
    private static final String NEW_PASSWORD = "NuovaPassword456!";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PasswordResetCleanupJob cleanupJob;

    @Test
    void fullFlowChangesPasswordAndClosesEverySession() {
        User hiker = createUser(Role.HIKER);
        JsonNode tokens = login(hiker.getEmail(), TEST_PASSWORD);
        String access = tokens.get("accessToken").asString();

        requestAndAwait(hiker);
        useKnownCode(hiker.getId());

        // come lo scriverebbe l'utente: minuscole e trattino
        assertThat(confirm(hiker.getEmail(), "abcdefgh-23456789", NEW_PASSWORD).status()).isEqualTo(204);

        assertThat(loginStatus(hiker.getEmail(), TEST_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus(hiker.getEmail(), NEW_PASSWORD)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/users/me", access, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString())).status()).isEqualTo(401);

        // il codice vale una volta
        HttpResult reuse = confirm(hiker.getEmail(), KNOWN_CODE, TEST_PASSWORD);
        assertThat(reuse.status()).isEqualTo(422);
        assertThat(isProblemJson(reuse)).isTrue();
        assertThat(json(reuse.body()).get("type").asString()).endsWith("/invalid-reset-code");
    }

    @Test
    void unknownEmailLooksLikeAKnownOne() {
        assertThat(request("nessuno@geosentinel.dev").status()).isEqualTo(204);
        // l'executor ha un solo thread: quando il codice di known esiste, la richiesta sconosciuta è già passata
        User known = createUser(Role.HIKER);
        requestAndAwait(known);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM password_reset_tokens", UUID.class))
                .containsExactly(known.getId());
        assertThat(confirm("nessuno@geosentinel.dev", KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
    }

    @Test
    void wrongCodesAreCountedAndBurnTheCode() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);
        useKnownCode(hiker.getId());

        for (int i = 0; i < 5; i++) {
            assertThat(confirm(hiker.getEmail(), "ZZZZZZZZZZZZZZZZ", NEW_PASSWORD).status()).isEqualTo(422);
        }
        // gli errori restano salvati nonostante il 422 (noRollbackFor) e al quinto il codice è consumato
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM password_reset_tokens WHERE user_id = ?", Integer.class, hiker.getId())).isEqualTo(5);
        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(loginStatus(hiker.getEmail(), TEST_PASSWORD)).isEqualTo(200);
    }

    @Test
    void malformedOrOversizedCodeIs422NotA500() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);

        // 32 caratteri da 3 byte: 96 byte, oltre il limite di BCrypt
        assertThat(confirm(hiker.getEmail(), "€".repeat(32), NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(confirm(hiker.getEmail(), "short", NEW_PASSWORD).status()).isEqualTo(422);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM password_reset_tokens WHERE user_id = ?", Integer.class, hiker.getId())).isEqualTo(2);
    }

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

    @Test
    void expiredCodeIsRejected() {
        User hiker = createUser(Role.HIKER);
        requestAndAwait(hiker);
        useKnownCode(hiker.getId());
        jdbcTemplate.update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?",
                hiker.getId());

        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, NEW_PASSWORD).status()).isEqualTo(422);
    }

    @Test
    void fourthRequestForTheSameEmailIsLimited() {
        User hiker = createUser(Role.HIKER);
        for (int i = 0; i < 3; i++) {
            requestAndAwait(hiker);
        }
        HttpResult limited = request(hiker.getEmail().toUpperCase());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.headers().getFirst("Retry-After")).isNotNull();
        // stesso limite per un'email inesistente: il 429 non dice se l'account c'è
        for (int i = 0; i < 3; i++) {
            request("ghost@geosentinel.dev");
        }
        assertThat(request("ghost@geosentinel.dev").status()).isEqualTo(429);
    }

    @Test
    void weakNewPasswordIs400() {
        User hiker = createUser(Role.HIKER);
        assertThat(confirm(hiker.getEmail(), KNOWN_CODE, "corta").status()).isEqualTo(400);
    }

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

    // POST della richiesta + attesa del codice scritto dal listener (asincrono)
    private void requestAndAwait(User user) {
        int before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, user.getId());
        assertThat(request(user.getEmail()).status()).isEqualTo(204);
        awaitTokens(user.getId(), before + 1);
    }

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

    private void useKnownCode(UUID userId) {
        jdbcTemplate.update("UPDATE password_reset_tokens SET code_hash = ? WHERE user_id = ? AND consumed_at IS NULL",
                passwordEncoder.encode(KNOWN_CODE), userId);
    }

    private HttpResult request(String email) {
        return call(HttpMethod.POST, "/api/v1/auth/password-reset/request", null, "{\"email\":\"%s\"}".formatted(email));
    }

    private HttpResult confirm(String email, String code, String newPassword) {
        return call(HttpMethod.POST, "/api/v1/auth/password-reset/confirm", null,
                "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}".formatted(email, code, newPassword));
    }

    private JsonNode login(String email, String password) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password));
        assertThat(result.status()).isEqualTo(200);
        return json(result.body());
    }

    private int loginStatus(String email, String password) {
        return call(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password)).status();
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }
}
