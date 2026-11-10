package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.application.port.GoogleIdentity;
import it.unical.geosentinel.identity.application.port.GoogleIdentityVerifier;
import it.unical.geosentinel.identity.application.port.MfaAlertMailer;
import it.unical.geosentinel.identity.domain.MfaSecretCipher;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.Totp;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// M8: il secondo fattore non si aggira (replay, brute force, Google, login paralleli) e il reset da ADMIN chiude tutto.
// I codici TOTP si calcolano dal segreto salvato (decifrato con il bean vero): nessun segreto letterale nei test
class MfaIT extends AbstractIntegrationTest {
    private static final String NEW_PASSWORD = "NuovaPassword456!";

    @MockitoBean
    private MfaAlertMailer mailer;

    @MockitoBean
    private GoogleIdentityVerifier googleVerifier;

    @Autowired
    private MfaSecretCipher cipher;

    @Autowired
    private StringRedisTemplate redis;

    // ---- supporto ---------------------------------------------------------------------------------------------------

    // Lontano dal cambio di passo: un codice calcolato qui vale ancora quando arriva al server
    private static void awaitSafeStep() throws InterruptedException {
        long inStep = Instant.now().getEpochSecond() % 30;
        if (inStep >= 22) {
            Thread.sleep((31 - inStep) * 1000);
        }
    }

    private byte[] secretOf(UUID userId) {
        byte[] enc = jdbcTemplate.queryForObject("SELECT secret_enc FROM user_mfa WHERE user_id = ?", byte[].class, userId);
        return cipher.decrypt(enc);
    }

    private String totp(UUID userId, long offset) {
        long step = Totp.stepAt(Instant.now()) + offset;
        return String.format(Locale.ROOT, "%06d", Totp.codeAt(secretOf(userId), step));
    }

    private HttpResult mfa(String action, String token, String code) {
        String body = code == null ? null : "{\"code\":\"%s\"}".formatted(code);
        return call(HttpMethod.POST, "/api/v1/users/me/mfa/" + action, token, body);
    }

    private HttpResult login(User user, String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"%s}".formatted(user.getEmail(), TEST_PASSWORD, code));
    }

    private HttpResult googleLogin(String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/auth/google", null, "{\"idToken\":\"id-token\"%s}".formatted(code));
    }

    private String slug(HttpResult result) {
        JsonNode type = json(result.body()).get("type");
        return type == null ? "" : type.asString().substring(type.asString().lastIndexOf('/') + 1);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private int setups;

    // ADR-0026: il codice dello setup arriva per email (dopo il commit, su un altro thread); si aspetta l'n-esimo invio,
    // così dopo un secondo setup non si legge il codice del primo
    private String awaitEmailCode(User user) {
        setups++;
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(2000).times(setups)).sendSetupCode(eq(user.getEmail()), code.capture(), any());
        return code.getValue();
    }

    private HttpResult enable(String token, String code, String emailCode) {
        return call(HttpMethod.POST, "/api/v1/users/me/mfa/enable", token,
                "{\"code\":\"%s\",\"emailCode\":\"%s\"}".formatted(code, emailCode));
    }

    private HttpResult changePassword(String token, String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/users/me/password", token,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"%s}".formatted(TEST_PASSWORD, NEW_PASSWORD, code));
    }

    // 2FA attiva con il codice del passo attuale (quindi già consumato); restituisce i codici di recupero
    private List<String> enableMfa(User user, String token) {
        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        HttpResult enabled = enable(token, totp(user.getId(), 0), awaitEmailCode(user));
        assertThat(enabled.status()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        json(enabled.body()).get("codes").forEach(node -> codes.add(node.asString()));
        return codes;
    }

    // ---- test -------------------------------------------------------------------------------------------------------

    @Test
    void loginRequiresTheCodeAndTheSameCodeNeverWorksTwice() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        assertThat(json(call(HttpMethod.GET, "/api/v1/users/me", token, null).body()).get("mfaEnabled").asBoolean()).isFalse();

        HttpResult setup = mfa("setup", token, null);
        assertThat(json(setup.body()).get("otpauthUri").asString()).startsWith("otpauth://totp/GeoSentinel:");
        assertThat(setup.headers().getCacheControl()).contains("no-store");
        String enableCode = totp(hiker.getId(), 0);
        HttpResult enabled = enable(token, enableCode, awaitEmailCode(hiker));
        assertThat(json(enabled.body()).get("codes")).hasSize(10);
        assertThat(json(call(HttpMethod.GET, "/api/v1/users/me", token, null).body()).get("mfaEnabled").asBoolean()).isTrue();
        verify(mailer, timeout(2000)).sendStatusChanged(hiker.getEmail(), true);

        HttpResult missing = login(hiker, null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(slug(missing)).isEqualTo("mfa-required");

        // il codice dell'attivazione è consumato: non apre una sessione
        HttpResult replay = login(hiker, enableCode);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(slug(replay)).isEqualTo("invalid-mfa-code");

        String next = totp(hiker.getId(), 1);
        assertThat(login(hiker, next).status()).isEqualTo(200);
        assertThat(login(hiker, next).status()).isEqualTo(401);
        // un codice di due passi fa è fuori dalla finestra
        assertThat(login(hiker, totp(hiker.getId(), -2)).status()).isEqualTo(401);
        // password sbagliata: 401 di credenziali, mai mfa-required (che confermerebbe la password)
        HttpResult wrongPassword = call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%sx\"}".formatted(hiker.getEmail(), TEST_PASSWORD));
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(slug(wrongPassword)).isNotEqualTo("mfa-required");
    }

    @Test
    void setupDoesNotProtectUntilConfirmedAndCanBeRestarted() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);

        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        String oldCode = totp(hiker.getId(), 0);
        String oldEmailCode = awaitEmailCode(hiker);
        assertThat(login(hiker, null).status()).isEqualTo(200);

        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        String emailCode = awaitEmailCode(hiker);
        // il segreto vecchio non vale più (salvo una collisione di 1 su 10^6 tra i due codici), né il codice email vecchio
        if (!oldCode.equals(totp(hiker.getId(), 0))) {
            assertThat(enable(token, oldCode, emailCode).status()).isEqualTo(422);
        }
        assertThat(enable(token, totp(hiker.getId(), 0), oldEmailCode).status()).isEqualTo(422);
        assertThat(enable(token, totp(hiker.getId(), 0), emailCode).status()).isEqualTo(200);
        HttpResult again = mfa("setup", token, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(slug(again)).isEqualTo("mfa-already-enabled");
    }

    @Test
    void recoveryCodeWorksOnceAndIsCaseAndDashInsensitive() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        List<String> recovery = enableMfa(hiker, tokenFor(hiker));

        String typed = recovery.getFirst().toLowerCase(Locale.ROOT);
        assertThat(login(hiker, typed).status()).isEqualTo(200);
        assertThat(login(hiker, typed).status()).isEqualTo(401);
        assertThat(login(hiker, recovery.get(1).replace("-", "")).status()).isEqualTo(200);
    }

    @Test
    void bruteForceIsThrottledEvenWithTheRightPasswordAndAlertsOnce() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        enableMfa(hiker, tokenFor(hiker));
        String good = totp(hiker.getId(), 1);
        String wrong = good.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThat(login(hiker, wrong).status()).isEqualTo(401);
        }
        // al sesto tentativo neanche il codice giusto passa
        HttpResult blocked = login(hiker, good);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.headers().getFirst("Retry-After")).isNotNull();

        // una sola email per finestra, anche dopo altri errori
        verify(mailer, timeout(2000)).sendWrongCodeWarning(hiker.getEmail());
        verify(mailer, after(500).times(1)).sendWrongCodeWarning(hiker.getEmail());
    }

    @Test
    void wrongCodesOnAccountRoutesAreLimitedButNeverEmailed() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        enableMfa(hiker, token);

        for (int i = 0; i < 5; i++) {
            HttpResult result = mfa("disable", token, "999999");
            assertThat(result.status()).isEqualTo(422);
            assertThat(slug(result)).isEqualTo("invalid-mfa-code");
        }
        assertThat(mfa("recovery-codes", token, totp(hiker.getId(), 1)).status()).isEqualTo(429);
        verify(mailer, after(500).never()).sendWrongCodeWarning(anyString());
    }

    // Login paralleli con lo stesso codice: il lock sull'utente ne lascia passare uno solo
    @RepeatedTest(5)
    void parallelLoginsWithTheSameCodeOpenOneSession() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        enableMfa(hiker, tokenFor(hiker));
        String code = totp(hiker.getId(), 1);

        List<Integer> statuses = runConcurrently(8, i -> login(hiker, code).status());

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 401 || s == 429);
    }

    @Test
    void regeneratedRecoveryCodesReplaceTheOldOnes() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        List<String> old = enableMfa(hiker, token);

        // un codice di recupero non ne genera altri
        assertThat(mfa("recovery-codes", token, old.getFirst()).status()).isEqualTo(422);
        HttpResult regenerated = mfa("recovery-codes", token, totp(hiker.getId(), 1));
        assertThat(regenerated.status()).isEqualTo(200);

        assertThat(login(hiker, old.get(1)).status()).isEqualTo(401);
        assertThat(login(hiker, json(regenerated.body()).get("codes").get(0).asString()).status()).isEqualTo(200);
    }

    @Test
    void disableNeedsAValidCodeThenLoginIsPasswordOnly() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        List<String> recovery = enableMfa(hiker, token);

        assertThat(mfa("disable", token, recovery.getFirst()).status()).isEqualTo(204);
        verify(mailer, timeout(2000)).sendStatusChanged(hiker.getEmail(), false);
        assertThat(login(hiker, null).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ?", hiker.getId())).isZero();
        assertThat(count("SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ?", hiker.getId())).isZero();
    }

    @Test
    void googleLoginCannotSkipTheSecondFactor() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        enableMfa(hiker, tokenFor(hiker));
        when(googleVerifier.verify(anyString()))
                .thenReturn(new GoogleIdentity("google-sub-" + UUID.randomUUID(), hiker.getEmail(), true, "Mario"));

        // collegamento di un account esistente: senza codice niente sessione e niente identità collegata
        HttpResult linkWithout = googleLogin(null);
        assertThat(linkWithout.status()).isEqualTo(401);
        assertThat(slug(linkWithout)).isEqualTo("mfa-required");
        assertThat(count("SELECT count(*) FROM user_identities WHERE user_id = ?", hiker.getId())).isZero();

        assertThat(googleLogin(totp(hiker.getId(), 1)).status()).isEqualTo(200);

        // identità già collegata: il codice serve ancora
        HttpResult linked = googleLogin(null);
        assertThat(linked.status()).isEqualTo(401);
        assertThat(slug(linked)).isEqualTo("mfa-required");
    }

    @Test
    void wrongCodesOnGoogleLoginStillSendTheAlert() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        enableMfa(hiker, tokenFor(hiker));
        when(googleVerifier.verify(anyString()))
                .thenReturn(new GoogleIdentity("google-sub-" + UUID.randomUUID(), hiker.getEmail(), true, "Mario"));

        for (int i = 0; i < 3; i++) {
            assertThat(googleLogin("999999").status()).isEqualTo(401);
        }
        // senza noRollbackFor su loginWithGoogle il rollback cancellava l'evento: nessuna email
        verify(mailer, timeout(2000)).sendWrongCodeWarning(hiker.getEmail());
    }

    @Test
    void adminResetClosesEverySessionAndEmailsTheUser() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String hikerToken = tokenFor(hiker);
        enableMfa(hiker, hikerToken);
        JsonNode session = json(login(hiker, totp(hiker.getId(), 1)).body());
        User admin = createUser(Role.ADMIN);
        String adminToken = tokenFor(admin);
        String body = "{\"email\":\"%s\"}".formatted(hiker.getEmail());

        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", tokenFor(createUser(Role.HIKER)), body).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", tokenFor(createUser(Role.PARK_MANAGER)), body).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken,
                "{\"email\":\"%s\"}".formatted(admin.getEmail())).status()).isEqualTo(422); // business-rule: serve un secondo ADMIN
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken,
                "{\"email\":\"nessuno@geosentinel.dev\"}").status()).isEqualTo(404);

        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken, body).status()).isEqualTo(204);

        assertThat(call(HttpMethod.GET, "/api/v1/users/me", hikerToken, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(session.get("refreshToken").asString())).status()).isEqualTo(401);
        assertThat(login(hiker, null).status()).isEqualTo(200);
        verify(mailer, timeout(2000)).sendAdminReset(hiker.getEmail());
        // un secondo reset non trova più la 2FA
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken, body).status()).isEqualTo(404);
    }

    @Test
    void accountDeletionRemovesEveryMfaRow() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        enableMfa(hiker, token);

        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", token, null).status()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ?", hiker.getId())).isZero();
        assertThat(count("SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ?", hiker.getId())).isZero();
    }

    // ---- ADR-0026 (audit del 10/11) ---------------------------------------------------------------------------------

    // Pista 5: chi ha registrato l'email di un altro non riceve il codice e non può attivare la 2FA
    @Test
    void enableNeedsTheCodeSentToTheAccountEmail() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        String emailCode = awaitEmailCode(hiker);

        assertThat(mfa("enable", token, totp(hiker.getId(), 0)).status()).isEqualTo(400); // emailCode assente
        HttpResult wrong = enable(token, totp(hiker.getId(), 0), "ABCDEFGH-23456789");
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(slug(wrong)).isEqualTo("invalid-mfa-code");
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ? AND enabled_at IS NULL", hiker.getId())).isOne();

        // come lo scriverebbe l'utente: minuscole, senza trattino
        String typed = emailCode.toLowerCase(Locale.ROOT).replace("-", "");
        assertThat(enable(token, totp(hiker.getId(), 0), typed).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ? AND email_code_hash IS NULL", hiker.getId())).isOne();
    }

    // Pista 3: il cambio password apre una sessione nuova, quindi chiede il secondo fattore (422, mai 401)
    @Test
    void changePasswordNeedsTheSecondFactor() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        enableMfa(hiker, token);

        HttpResult missing = changePassword(token, null);
        assertThat(missing.status()).isEqualTo(422);
        assertThat(slug(missing)).isEqualTo("invalid-mfa-code");
        assertThat(changePassword(token, "999999").status()).isEqualTo(422);
        assertThat(changePassword(token, totp(hiker.getId(), 1)).status()).isEqualTo(200);
    }

    // Pista 2: chi conosce la password svuota il bucket del login, ma il titolare cambia la password in sessione
    // (bucket separato) e così sblocca anche il login
    @Test
    void drainedLoginBucketDoesNotBlockTheOwnerInSession() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        enableMfa(hiker, token);
        String good = totp(hiker.getId(), 1);
        String wrong = good.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(login(hiker, wrong).status()).isEqualTo(401);
        }
        assertThat(login(hiker, good).status()).isEqualTo(429);

        assertThat(changePassword(token, good).status()).isEqualTo(200);
        assertThat(redis.hasKey("rl:mfa:" + hiker.getId())).isFalse();
    }

    // Pista 5: il collegamento a Google chiude fuori anche i telefoni di chi aveva registrato l'email
    @Test
    void googleLinkRemovesTheDevicesOfThePreRegisteredAccount() {
        User preRegistered = createUser(Role.HIKER);
        assertThat(call(HttpMethod.POST, "/api/v1/devices", tokenFor(preRegistered),
                "{\"token\":\"fcm-%s\",\"platform\":\"ANDROID\"}".formatted(UUID.randomUUID())).status()).isLessThan(300);
        assertThat(count("SELECT count(*) FROM device_tokens WHERE user_id = ?", preRegistered.getId())).isOne();
        when(googleVerifier.verify(anyString()))
                .thenReturn(new GoogleIdentity("google-sub-" + UUID.randomUUID(), preRegistered.getEmail(), true, "Mario"));

        assertThat(googleLogin(null).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM device_tokens WHERE user_id = ?", preRegistered.getId())).isZero();
    }
}
