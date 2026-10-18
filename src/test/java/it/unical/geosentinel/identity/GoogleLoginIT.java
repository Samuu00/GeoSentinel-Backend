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

// ADR-0024. La verifica della firma (Google) è sostituita: qui conta cosa fa il backend con un'identità già verificata
class GoogleLoginIT extends AbstractIntegrationTest {

    @MockitoBean
    private GoogleIdentityVerifier verifier;

    private final String adultBirthDate = LocalDate.now(ZoneOffset.UTC).minusYears(30).toString();

    private void googleSays(String subject, String email, boolean verified) {
        when(verifier.verify(anyString())).thenReturn(new GoogleIdentity(subject, email, verified, "Mario Rossi"));
    }

    private HttpResult googleLogin(String birthDateJson) {
        return call(HttpMethod.POST, "/api/v1/auth/google", null,
                "{\"idToken\":\"id-token\",\"birthDate\":%s}".formatted(birthDateJson));
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void firstLoginCreatesAccountWithoutPasswordThenLogsIn() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-1", email, true);

        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(201);
        // Il secondo accesso non chiede la data di nascita
        assertThat(googleLogin("null").status()).isEqualTo(200);

        assertThat(count("SELECT count(*) FROM users WHERE email = ? AND password_hash IS NULL", email)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_identities WHERE subject = 'sub-1'")).isEqualTo(1);
    }

    @Test
    void newAccountWithoutBirthDateIs422AndNothingIsStored() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-2", email, true);

        HttpResult result = googleLogin("null");
        assertThat(result.status()).isEqualTo(422);
        // slug proprio: l'app sa che deve chiedere la data e ripetere la richiesta
        assertThat(json(result.body()).get("type").asString()).endsWith("/birth-date-required");
        assertThat(count("SELECT count(*) FROM users WHERE email = ?", email)).isZero();
    }

    // Un account Google con l'email di un altro, non verificata, non deve creare né aprire nulla
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

    // La registrazione non verifica l'email: chi l'aveva registrata perde password e sessioni
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

    @Test
    void staffAccountIsNotLinked() {
        User manager = createUser(Role.PARK_MANAGER);
        googleSays("sub-4", manager.getEmail(), true);

        HttpResult result = googleLogin("null");

        assertThat(result.status()).isEqualTo(409);
        assertThat(json(result.body()).get("type").asString()).endsWith("/account-managed");
    }

    // Stessa email, un secondo account Google: l'identità già collegata non si affianca a un'altra
    @Test
    void accountAlreadyLinkedToAnotherGoogleAccountIsNotRelinked() {
        String email = UUID.randomUUID() + "@gmail.com";
        googleSays("sub-owner", email, true);
        assertThat(googleLogin("\"" + adultBirthDate + "\"").status()).isEqualTo(201);

        googleSays("sub-intruder", email, true);
        assertThat(googleLogin("null").status()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM user_identities WHERE subject = 'sub-intruder'")).isZero();
    }

    // Doppio tap o retry sul primo accesso: un solo account, nessun 500 dal vincolo UNIQUE
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
