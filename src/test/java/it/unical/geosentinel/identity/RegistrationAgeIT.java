// Test di integrazione del controllo sull'età minima in fase di registrazione, con chiamate HTTP vere e
// PostGIS in Testcontainers. Il GDPR (art. 8) fissa in Italia a 14 anni l'età minima per dare il consenso
// da soli: la data di nascita serve solo per questo controllo e non viene mai salvata nel database.
package it.unical.geosentinel.identity;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica l'accettazione e il rifiuto delle registrazioni in base alla data di nascita, e che lo schema
 * non contenga colonne con la data di nascita.
 */
class RegistrationAgeIT extends AbstractIntegrationTest {

    /** Data odierna in UTC, la stessa zona usata dal server per il calcolo dell'età. */
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    /**
     * Un utente che compie 14 anni oggi deve potersi registrare (201) e nel database deve risultare registrato
     * l'istante di conferma dell'età.
     */
    @Test
    void fourteenTodayIsAccepted() {
        String email = UUID.randomUUID() + "@test.dev";

        assertThat(register(email, "\"" + today.minusYears(14) + "\"").status()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT age_confirmed_at IS NOT NULL FROM users WHERE email = ?", Boolean.class, email)).isTrue();
    }

    /**
     * Un utente a cui manca un giorno ai 14 anni deve essere rifiutato con 422 e slug
     * {@code age-requirement-not-met}, senza che venga salvato alcun utente.
     */
    @Test
    void oneDayBeforeFourteenthBirthdayIsRejectedAndNothingIsStored() {
        String email = UUID.randomUUID() + "@test.dev";

        HttpResult result = register(email, "\"" + today.minusYears(14).plusDays(1) + "\"");

        assertThat(result.status()).isEqualTo(422);
        assertThat(json(result.body()).get("type").asString()).endsWith("/age-requirement-not-met");
        // Il messaggio di errore è generico e non rivela la soglia di età.
        assertThat(json(result.body()).get("detail").asString()).doesNotContain("14");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    /** Una data di nascita nel futuro o assente è un errore di validazione: ci si aspetta 400. */
    @Test
    void futureOrMissingBirthDateIsA400() {
        assertThat(register(UUID.randomUUID() + "@test.dev", "\"" + today.plusDays(1) + "\"").status()).isEqualTo(400);
        assertThat(register(UUID.randomUUID() + "@test.dev", "null").status()).isEqualTo(400);
    }

    /** La tabella {@code users} non deve avere nessuna colonna il cui nome contenga "birth". */
    @Test
    void schemaHasNoBirthDateColumn() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'users' AND column_name LIKE '%birth%'
                """, Integer.class)).isZero();
    }

    /**
     * Invia una richiesta di registrazione con password valida.
     *
     * @param email         l'email del nuovo utente
     * @param birthDateJson la data di nascita già in forma JSON (stringa tra virgolette oppure {@code null})
     * @return la risposta del server
     */
    private HttpResult register(String email, String birthDateJson) {
        return call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s","password":"%s","displayName":"Giovane","birthDate":%s}
                """.formatted(email, TEST_PASSWORD, birthDateJson));
    }
}
