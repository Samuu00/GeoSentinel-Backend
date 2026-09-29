package it.unical.geosentinel.identity;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// M7.2.1: età minima GDPR art. 8 (14 anni in Italia), la data di nascita non si salva
class RegistrationAgeIT extends AbstractIntegrationTest {

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @Test
    void fourteenTodayIsAccepted() {
        String email = UUID.randomUUID() + "@test.dev";

        assertThat(register(email, "\"" + today.minusYears(14) + "\"").status()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT age_confirmed_at IS NOT NULL FROM users WHERE email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    void oneDayBeforeFourteenthBirthdayIsRejectedAndNothingIsStored() {
        String email = UUID.randomUUID() + "@test.dev";

        HttpResult result = register(email, "\"" + today.minusYears(14).plusDays(1) + "\"");

        assertThat(result.status()).isEqualTo(422);
        assertThat(json(result.body()).get("type").asString()).endsWith("/age-requirement-not-met");
        // detail generico: la soglia non si rivela
        assertThat(json(result.body()).get("detail").asString()).doesNotContain("14");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void futureOrMissingBirthDateIsA400() {
        assertThat(register(UUID.randomUUID() + "@test.dev", "\"" + today.plusDays(1) + "\"").status()).isEqualTo(400);
        assertThat(register(UUID.randomUUID() + "@test.dev", "null").status()).isEqualTo(400);
    }

    @Test
    void schemaHasNoBirthDateColumn() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'users' AND column_name LIKE '%birth%'
                """, Integer.class)).isZero();
    }

    private HttpResult register(String email, String birthDateJson) {
        return call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s","password":"%s","displayName":"Giovane","birthDate":%s}
                """.formatted(email, TEST_PASSWORD, birthDateJson));
    }
}
