// Test di integrazione del login con Google (POST /auth/google), con chiamate HTTP vere e PostGIS e Redis in
// Testcontainers. La verifica della firma dell'ID token di Google è sostituita da un mock che restituisce
// direttamente un'identità già verificata: qui conta cosa fa il backend con quell'identità, cioè creare un
// account, collegarlo a uno esistente o rifiutare la richiesta.
package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.application.port.GoogleIdentity;
import it.unical.geosentinel.identity.application.port.GoogleIdentityVerifier;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifica creazione dell'account al primo accesso, collegamento a un account esistente e casi in cui il
 * collegamento deve essere rifiutato.
 */
class GoogleLoginIT extends AbstractIntegrationTest {

    /** Verificatore finto degli ID token, che sostituisce il bean reale nel contesto Spring. */
    @MockitoBean
    private GoogleIdentityVerifier verifier;

    /** Data di nascita di un adulto (30 anni fa), in formato ISO. */
    private final String adultBirthDate = LocalDate.now(ZoneOffset.UTC).minusYears(30).toString();

    /**
     * Fa restituire al verificatore finto l'identità Google indicata, per qualsiasi token.
     *
     * @param subject  l'identificativo stabile dell'utente su Google ({@code sub})
     * @param email    l'email dell'account Google
     * @param verified se Google dichiara l'email verificata
     */
    private void googleSays(String subject, String email, boolean verified) {
        when(verifier.verify(anyString())).thenReturn(new GoogleIdentity(subject, email, verified, "Mario Rossi"));
    }

    /**
     * Esegue il login con Google.
     *
     * @param birthDateJson la data di nascita già in forma JSON (stringa tra virgolette oppure {@code null})
     * @return la risposta del server
     */
    private HttpResult googleLogin(String birthDateJson) {
        return call(HttpMethod.POST, "/api/v1/auth/google", null,
                "{\"idToken\":\"id-token\",\"birthDate\":%s}".formatted(birthDateJson));
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

    /**
     * Al primo accesso con un'email verificata si crea un account senza password (201) e un'identità Google
     * collegata; l'accesso successivo entra nello stesso account (200).
     */
    @Test
    void firstLoginCreatesAccountWithoutPasswordThenLogsIn() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-1", email, true);

        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(201);
        // Dal secondo accesso la data di nascita non serve più.
        assertThat(googleLogin("null").status()).isEqualTo(200);

        assertThat(count("SELECT count(*) FROM users WHERE email = ? AND password_hash IS NULL", email)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_identities WHERE subject = 'sub-1'")).isEqualTo(1);
    }

    /**
     * Se l'account va creato ma manca la data di nascita, la risposta è 422 con lo slug
     * {@code birth-date-required} e non viene salvato nulla. Lo slug dedicato dice all'app che deve chiedere
     * la data e ripetere la richiesta.
     */
    @Test
    void newAccountWithoutBirthDateIs422AndNothingIsStored() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-2", email, true);

        HttpResult result = googleLogin("null");
        assertThat(result.status()).isEqualTo(422);
        assertThat(json(result.body()).get("type").asString()).endsWith("/birth-date-required");
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    /**
     * Un account Google con un'email non verificata non deve né collegarsi a un account esistente con la
     * stessa email né crearne uno nuovo: altrimenti chiunque potrebbe dichiarare l'email di un altro.
     */
    @Test
    void unverifiedEmailNeverCreatesOrLinks() {
        User victim = createUser(Role.HIKER);
        googleSays("attacker", victim.getEmail(), false);
        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(422);

        String fresh = UUID.randomUUID() + "@example.com";
        googleSays("attacker", fresh, false);
        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(422);

        assertThat(count("SELECT count(*) FROM users WHERE email = ?", fresh)).isZero();
        assertThat(count("SELECT count(*) FROM user_identities")).isZero();
    }

    /**
     * Con un'email verificata da Google, l'account escursionista esistente con la stessa email viene collegato
     * e perde la password. La registrazione classica non verifica l'email, quindi chi l'aveva registrata
     * potrebbe non esserne il proprietario: dopo il collegamento il login con la vecchia password dà 401.
     */
    @Test
    void verifiedEmailLinksExistingHikerAndDropsItsPassword() {
        User hiker = createUser(Role.HIKER);
        googleSays("sub-3", hiker.getEmail(), true);

        assertThat(googleLogin("null").status()).isEqualTo(200);

        assertThat(count("SELECT count(*) FROM users WHERE id = ? AND password_hash IS NULL", hiker.getId())).isEqualTo(1);
        HttpResult passwordLogin = call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(hiker.getEmail(), TEST_PASSWORD));
        assertThat(passwordLogin.status()).isEqualTo(401);
    }

    /** Un account del personale (qui un gestore del parco) non viene collegato a Google: 409 {@code account-managed}. */
    @Test
    void staffAccountIsNotLinked() {
        User manager = createUser(Role.PARK_MANAGER);
        googleSays("sub-4", manager.getEmail(), true);

        HttpResult result = googleLogin("null");

        assertThat(result.status()).isEqualTo(409);
        assertThat(json(result.body()).get("type").asString()).endsWith("/account-managed");
    }

    /**
     * Un account già collegato a un'identità Google non deve essere collegato anche a un secondo account Google
     * con la stessa email: la richiesta dà 422 e la seconda identità non viene salvata.
     */
    @Test
    void accountAlreadyLinkedToAnotherGoogleAccountIsNotRelinked() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-owner", email, true);
        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(201);

        googleSays("sub-intruder", email, true);
        assertThat(googleLogin("null").status()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM user_identities WHERE subject = 'sub-intruder'")).isZero();
    }

    /**
     * Dieci primi accessi simultanei (doppio tocco o nuovi tentativi dell'app) devono creare un solo account:
     * un 201 e gli altri 200, senza errori 500 dovuti alla violazione del vincolo UNIQUE sull'email.
     */
    @RepeatedTest(5)
    void concurrentFirstLoginsCreateOneAccount() throws Exception {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-race-" + UUID.randomUUID(), email, true);

        List<HttpResult> results = runConcurrently(10, i -> googleLogin("\"" + adultBirthDate + "\""));

        assertThat(results).extracting(HttpResult::status).containsOnly(200, 201);
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }
}
