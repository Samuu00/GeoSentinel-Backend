// Test unitario del vincolo di validazione @MaxUtf8Bytes, usato sulla password di registrazione.
// Il limite è espresso in byte UTF-8 (72, il massimo che BCrypt considera) e non in caratteri: il test lo
// verifica con il Bean Validation standard, senza avviare Spring.
package it.unical.geosentinel.shared.web.validation;

import it.unical.geosentinel.identity.web.dto.RegisterRequest;
import jakarta.validation.Validation;
import java.time.LocalDate;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che il campo password di {@link RegisterRequest} venga rifiutato quando supera i 72 byte in UTF-8,
 * anche se il numero di caratteri resterebbe sotto il limite.
 */
class MaxUtf8BytesTest {

    /** Validatore Jakarta costruito con la configurazione di default, senza contesto Spring. */
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * Valida una richiesta di registrazione con la password indicata e gli altri campi validi.
     *
     * @param password la password da provare
     * @return {@code true} se non ci sono violazioni sul campo {@code password}
     */
    private boolean passwordValid(String password) {
        return validator.validate(new RegisterRequest("a@example.com", password, "Mario", LocalDate.of(1990, 1, 1)))
                .stream().noneMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    /**
     * Con caratteri ASCII (un byte ciascuno) il limite cade a 72; con caratteri accentati (due byte ciascuno)
     * cade a 36 caratteri. Il risultato atteso è che la validazione conti i byte e non i caratteri.
     */
    @Test
    void limiteInByteNonInCaratteri() {
        assertThat(passwordValid("a".repeat(72))).isTrue();
        assertThat(passwordValid("a".repeat(73))).isFalse();
        assertThat(passwordValid("a".repeat(80))).isFalse();
        // 40 caratteri accentati occupano 80 byte: un semplice @Size sui caratteri li avrebbe accettati.
        assertThat(passwordValid("è".repeat(40))).isFalse();
        assertThat(passwordValid("è".repeat(36))).isTrue();
    }
}
