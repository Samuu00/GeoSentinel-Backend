package it.unical.geosentinel.shared.web.validation;

import it.unical.geosentinel.identity.web.dto.RegisterRequest;
import jakarta.validation.Validation;
import java.time.LocalDate;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean passwordValid(String password) {
        return validator.validate(new RegisterRequest("a@example.com", password, "Mario", LocalDate.of(1990, 1, 1)))
                .stream().noneMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    @Test
    void limiteInByteNonInCaratteri() {
        assertThat(passwordValid("a".repeat(72))).isTrue();
        assertThat(passwordValid("a".repeat(73))).isFalse();
        assertThat(passwordValid("a".repeat(80))).isFalse();
        // 40 caratteri accentati = 80 byte: @Size da solo li avrebbe accettati
        assertThat(passwordValid("è".repeat(40))).isFalse();
        assertThat(passwordValid("è".repeat(36))).isTrue();
    }
}
