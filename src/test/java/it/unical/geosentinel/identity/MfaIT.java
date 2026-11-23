// Test di integrazione della verifica in due passaggi (codici TOTP generati da un'app di autenticazione), con
// chiamate HTTP vere e PostGIS e Redis in Testcontainers. Controlla che il secondo fattore non si possa
// aggirare (riuso dello stesso codice, tentativi a ripetizione, login con Google, login paralleli) e che
// l'azzeramento da parte di un amministratore chiuda tutte le sessioni. I codici TOTP vengono calcolati dal
// segreto salvato nel database, decifrato con il bean reale: nei test non compare nessun segreto scritto a mano.
// Le email (codice di attivazione e avvisi) sono intercettate da un mock del mailer.
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

/**
 * Verifica attivazione, uso, limiti, disattivazione e azzeramento della verifica in due passaggi.
 */
class MfaIT extends AbstractIntegrationTest {

    /** Nuova password usata nei test sul cambio password. */
    private static final String NEW_PASSWORD = "NuovaPassword456!";

    /** Mailer finto, usato per leggere il codice di attivazione e verificare gli avvisi inviati. */
    @MockitoBean
    private MfaAlertMailer mailer;

    /** Verificatore finto degli ID token di Google. */
    @MockitoBean
    private GoogleIdentityVerifier googleVerifier;

    /** Cifratore reale dei segreti, usato per leggere il segreto TOTP salvato. */
    @Autowired
    private MfaSecretCipher cipher;

    /** Accesso diretto a Redis, per controllare il contatore dei tentativi della verifica in due passaggi. */
    @Autowired
    private StringRedisTemplate redis;

    // ---- supporto ---------------------------------------------------------------------------------------------------

    /**
     * Se mancano meno di 9 secondi alla fine del passo TOTP corrente (30 secondi), aspetta l'inizio del passo
     * successivo. Così un codice calcolato dal test è ancora valido quando arriva al server.
     *
     * @throws InterruptedException se il thread viene interrotto durante l'attesa
     */
    private static void awaitSafeStep() throws InterruptedException {
        long inStep = Instant.now().getEpochSecond() % 30;
        if (inStep >= 22) {
            Thread.sleep((31 - inStep) * 1000);
        }
    }

    /**
     * Legge e decifra il segreto TOTP salvato per l'utente.
     *
     * @param userId l'id dell'utente
     * @return il segreto in chiaro
     */
    private byte[] secretOf(UUID userId) {
        byte[] enc = jdbcTemplate.queryForObject("SELECT secret_enc FROM user_mfa WHERE user_id = ?", byte[].class, userId);
        return cipher.decrypt(enc);
    }

    /**
     * Calcola il codice TOTP dell'utente per un passo vicino a quello attuale.
     *
     * @param userId l'id dell'utente
     * @param offset lo scarto in passi rispetto al passo attuale (0 il passo corrente, 1 il successivo)
     * @return il codice a 6 cifre
     */
    private String totp(UUID userId, long offset) {
        long step = Totp.stepAt(Instant.now()) + offset;
        return String.format(Locale.ROOT, "%06d", Totp.codeAt(secretOf(userId), step));
    }

    /**
     * Chiama una delle azioni sotto {@code /users/me/mfa}.
     *
     * @param action l'azione, ad esempio {@code setup}, {@code disable} o {@code recovery-codes}
     * @param token  l'access token dell'utente
     * @param code   il codice da mandare nel corpo, oppure {@code null} per una richiesta senza corpo
     * @return la risposta del server
     */
    private HttpResult mfa(String action, String token, String code) {
        String body = code == null ? null : "{\"code\":\"%s\"}".formatted(code);
        return call(HttpMethod.POST, "/api/v1/users/me/mfa/" + action, token, body);
    }

    /**
     * Esegue il login con la password giusta e, se indicato, il codice del secondo fattore.
     *
     * @param user    l'utente
     * @param mfaCode il codice TOTP o di recupero, oppure {@code null} per ometterlo
     * @return la risposta del server
     */
    private HttpResult login(User user, String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"%s}".formatted(user.getEmail(), TEST_PASSWORD, code));
    }

    /**
     * Esegue il login con Google e, se indicato, il codice del secondo fattore.
     *
     * @param mfaCode il codice, oppure {@code null} per ometterlo
     * @return la risposta del server
     */
    private HttpResult googleLogin(String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/auth/google", null, "{\"idToken\":\"id-token\"%s}".formatted(code));
    }

    /**
     * Estrae lo slug dell'errore dal campo {@code type} del ProblemDetail.
     *
     * @param result la risposta
     * @return l'ultima parte dell'URI in {@code type}, oppure una stringa vuota se manca
     */
    private String slug(HttpResult result) {
        JsonNode type = json(result.body()).get("type");
        return type == null ? "" : type.asString().substring(type.asString().lastIndexOf('/') + 1);
    }

    /**
     * Esegue una query di conteggio.
     *
     * @param sql  la query, che deve restituire un solo intero
     * @param args i parametri della query
     * @return il conteggio
     */
    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** Numero di avvii della configurazione fatti nel test corrente, cioè di email con il codice attese. */
    private int setups;

    /**
     * Aspetta l'email con il codice di attivazione e ne restituisce il codice. L'email parte dopo il commit e su
     * un altro thread; si aspetta l'n-esimo invio, così dopo una seconda configurazione non si legge il codice
     * della prima.
     *
     * @param user l'utente che ha avviato la configurazione
     * @return il codice ricevuto per email
     */
    private String awaitEmailCode(User user) {
        setups++;
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(2000).times(setups)).sendSetupCode(eq(user.getEmail()), code.capture(), any());
        return code.getValue();
    }

    /**
     * Conferma l'attivazione della verifica in due passaggi.
     *
     * @param token     l'access token dell'utente
     * @param code      il codice TOTP
     * @param emailCode il codice ricevuto per email
     * @return la risposta del server
     */
    private HttpResult enable(String token, String code, String emailCode) {
        return call(HttpMethod.POST, "/api/v1/users/me/mfa/enable", token,
                "{\"code\":\"%s\",\"emailCode\":\"%s\"}".formatted(code, emailCode));
    }

    /**
     * Cambia la password con quella attuale giusta e, se indicato, il codice del secondo fattore.
     *
     * @param token   l'access token dell'utente
     * @param mfaCode il codice, oppure {@code null} per ometterlo
     * @return la risposta del server
     */
    private HttpResult changePassword(String token, String mfaCode) {
        String code = mfaCode == null ? "" : ",\"mfaCode\":\"%s\"".formatted(mfaCode);
        return call(HttpMethod.POST, "/api/v1/users/me/password", token,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"%s}".formatted(TEST_PASSWORD, NEW_PASSWORD, code));
    }

    /**
     * Attiva la verifica in due passaggi usando il codice del passo attuale, che da quel momento risulta già
     * consumato.
     *
     * @param user  l'utente
     * @param token l'access token dell'utente
     * @return i codici di recupero restituiti all'attivazione
     */
    private List<String> enableMfa(User user, String token) {
        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        HttpResult enabled = enable(token, totp(user.getId(), 0), awaitEmailCode(user));
        assertThat(enabled.status()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        json(enabled.body()).get("codes").forEach(node -> codes.add(node.asString()));
        return codes;
    }

    // ---- test -------------------------------------------------------------------------------------------------------

    /**
     * Attivazione completa: la configurazione restituisce l'URI otpauth senza cache, l'attivazione restituisce
     * 10 codici di recupero e invia un'email di conferma. Da quel momento il login senza codice dà 401
     * {@code mfa-required}; il codice usato per l'attivazione e ogni codice già usato danno 401
     * {@code invalid-mfa-code}; un codice di due passi fa è fuori dalla finestra. Con la password sbagliata la
     * risposta è un 401 di credenziali e mai {@code mfa-required}, che confermerebbe la password.
     */
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

        // Il codice usato per l'attivazione è già consumato e non apre una sessione.
        HttpResult replay = login(hiker, enableCode);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(slug(replay)).isEqualTo("invalid-mfa-code");

        String next = totp(hiker.getId(), 1);
        assertThat(login(hiker, next).status()).isEqualTo(200);
        assertThat(login(hiker, next).status()).isEqualTo(401);
        assertThat(login(hiker, totp(hiker.getId(), -2)).status()).isEqualTo(401);
        HttpResult wrongPassword = call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%sx\"}".formatted(hiker.getEmail(), TEST_PASSWORD));
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(slug(wrongPassword)).isNotEqualTo("mfa-required");
    }

    /**
     * Una configurazione avviata ma non confermata non protegge ancora l'account (il login senza codice
     * riesce) e si può ricominciare. Dopo il secondo avvio, il segreto e il codice email del primo non valgono
     * più. Con la verifica già attiva, un nuovo avvio dà 409 {@code mfa-already-enabled}.
     */
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
        // Il codice del vecchio segreto si controlla solo se è diverso da quello nuovo: i due codici possono
        // coincidere per caso, con probabilità di uno su un milione.
        if (!oldCode.equals(totp(hiker.getId(), 0))) {
            assertThat(enable(token, oldCode, emailCode).status()).isEqualTo(422);
        }
        assertThat(enable(token, totp(hiker.getId(), 0), oldEmailCode).status()).isEqualTo(422);
        assertThat(enable(token, totp(hiker.getId(), 0), emailCode).status()).isEqualTo(200);
        HttpResult again = mfa("setup", token, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(slug(again)).isEqualTo("mfa-already-enabled");
    }

    /**
     * Un codice di recupero funziona una sola volta e viene accettato anche in minuscolo o senza trattino.
     */
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

    /**
     * Dopo 5 codici sbagliati con la password giusta, il sesto tentativo dà 429 con Retry-After anche se il
     * codice è giusto. All'utente arriva una sola email di avviso per finestra, anche con altri errori.
     */
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
        HttpResult blocked = login(hiker, good);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.headers().getFirst("Retry-After")).isNotNull();

        verify(mailer, timeout(2000)).sendWrongCodeWarning(hiker.getEmail());
        verify(mailer, after(500).times(1)).sendWrongCodeWarning(hiker.getEmail());
    }

    /**
     * Anche sulle rotte del proprio account (disattivazione, rigenerazione dei codici) i codici sbagliati sono
     * limitati: dopo 5 risposte 422 {@code invalid-mfa-code} arriva un 429. In questo caso non parte nessuna
     * email di avviso, perché chi sbaglia è già autenticato.
     */
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

    /**
     * Otto login in parallelo con lo stesso codice valido: il lock sull'utente li esegue uno alla volta e solo
     * il primo apre una sessione (200); gli altri ricevono 401 o, se il limite scatta, 429.
     */
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

    /**
     * I codici di recupero si rigenerano solo con un codice TOTP (un codice di recupero dà 422). Dopo la
     * rigenerazione i vecchi codici non valgono più e i nuovi sì.
     */
    @Test
    void regeneratedRecoveryCodesReplaceTheOldOnes() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        List<String> old = enableMfa(hiker, token);

        assertThat(mfa("recovery-codes", token, old.getFirst()).status()).isEqualTo(422);
        HttpResult regenerated = mfa("recovery-codes", token, totp(hiker.getId(), 1));
        assertThat(regenerated.status()).isEqualTo(200);

        assertThat(login(hiker, old.get(1)).status()).isEqualTo(401);
        assertThat(login(hiker, json(regenerated.body()).get("codes").get(0).asString()).status()).isEqualTo(200);
    }

    /**
     * La disattivazione richiede un codice valido (qui di recupero), invia un'email e cancella segreto e codici
     * di recupero; da quel momento basta la password per entrare.
     */
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

    /**
     * Il login con Google non salta il secondo fattore. Quando collega un account esistente, senza codice non
     * apre la sessione e non salva l'identità; con il codice riesce. Anche dopo il collegamento il codice resta
     * obbligatorio.
     */
    @Test
    void googleLoginCannotSkipTheSecondFactor() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        enableMfa(hiker, tokenFor(hiker));
        when(googleVerifier.verify(anyString()))
                .thenReturn(new GoogleIdentity("google-sub-" + UUID.randomUUID(), hiker.getEmail(), true, "Mario"));

        HttpResult linkWithout = googleLogin(null);
        assertThat(linkWithout.status()).isEqualTo(401);
        assertThat(slug(linkWithout)).isEqualTo("mfa-required");
        assertThat(count("SELECT count(*) FROM user_identities WHERE user_id = ?", hiker.getId())).isZero();

        assertThat(googleLogin(totp(hiker.getId(), 1)).status()).isEqualTo(200);

        HttpResult linked = googleLogin(null);
        assertThat(linked.status()).isEqualTo(401);
        assertThat(slug(linked)).isEqualTo("mfa-required");
    }

    /**
     * Anche i codici sbagliati nel login con Google devono far partire l'email di avviso.
     */
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
        // Il login con Google non annulla la transazione per questo errore (noRollbackFor): se lo facesse,
        // l'evento che fa partire l'email andrebbe perso.
        verify(mailer, timeout(2000)).sendWrongCodeWarning(hiker.getEmail());
    }

    /**
     * Solo un amministratore può azzerare la verifica in due passaggi di un utente (403 per escursionista e
     * gestore), non può azzerare la propria (422: serve un secondo amministratore) e riceve 404 per un'email
     * inesistente. L'azzeramento chiude tutte le sessioni dell'utente, gli permette di entrare con la sola
     * password e gli invia un'email; un secondo azzeramento dà 404.
     */
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
        // Un amministratore non può azzerare la propria verifica: la regola di business chiede un secondo ADMIN.
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken,
                "{\"email\":\"%s\"}".formatted(admin.getEmail())).status()).isEqualTo(422);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken,
                "{\"email\":\"nessuno@geosentinel.dev\"}").status()).isEqualTo(404);

        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken, body).status()).isEqualTo(204);

        assertThat(call(HttpMethod.GET, "/api/v1/users/me", hikerToken, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(session.get("refreshToken").asString())).status()).isEqualTo(401);
        assertThat(login(hiker, null).status()).isEqualTo(200);
        verify(mailer, timeout(2000)).sendAdminReset(hiker.getEmail());
        // La verifica non è più attiva, quindi un secondo azzeramento non trova nulla.
        assertThat(call(HttpMethod.POST, "/api/v1/admin/mfa-resets", adminToken, body).status()).isEqualTo(404);
    }

    /** La cancellazione dell'account elimina il segreto e i codici di recupero. */
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

    // ---- protezioni contro chi conosce la password o ha registrato l'email di un altro -------------------------------

    /**
     * L'attivazione richiede anche il codice inviato all'email dell'account: chi ha registrato l'email di un
     * altro non lo riceve e non può attivare la verifica. Senza codice email la richiesta dà 400, con un codice
     * sbagliato 422 {@code invalid-mfa-code} e la verifica resta non attiva. Il codice giusto, scritto in
     * minuscolo e senza trattino, viene accettato e poi cancellato.
     */
    @Test
    void enableNeedsTheCodeSentToTheAccountEmail() throws Exception {
        awaitSafeStep();
        User hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        assertThat(mfa("setup", token, null).status()).isEqualTo(200);
        String emailCode = awaitEmailCode(hiker);

        // Corpo senza emailCode: errore di validazione.
        assertThat(mfa("enable", token, totp(hiker.getId(), 0)).status()).isEqualTo(400);
        HttpResult wrong = enable(token, totp(hiker.getId(), 0), "ABCDEFGH-23456789");
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(slug(wrong)).isEqualTo("invalid-mfa-code");
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ? AND enabled_at IS NULL", hiker.getId())).isOne();

        String typed = emailCode.toLowerCase(Locale.ROOT).replace("-", "");
        assertThat(enable(token, totp(hiker.getId(), 0), typed).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM user_mfa WHERE user_id = ? AND email_code_hash IS NULL", hiker.getId())).isOne();
    }

    /**
     * Il cambio password apre una sessione nuova, quindi con la verifica attiva richiede il secondo fattore:
     * senza codice o con un codice sbagliato la risposta è 422 (mai 401, che l'app interpreterebbe come sessione
     * scaduta), con il codice giusto 200.
     */
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

    /**
     * Chi conosce la password può esaurire i tentativi del secondo fattore al login (429). Il proprietario,
     * già in sessione, può comunque cambiare la password, perché quella rotta usa un contatore separato; il
     * cambio azzera anche il contatore del login.
     */
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

    /**
     * Quando il login con Google collega un account registrato in precedenza con la stessa email, vengono
     * cancellati anche i dispositivi registrati da quell'account: chi aveva registrato l'email di un altro
     * smette di ricevere le notifiche.
     */
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
