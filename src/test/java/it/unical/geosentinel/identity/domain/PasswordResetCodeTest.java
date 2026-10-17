package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetCodeTest {

    @Test
    void generatedCodesAreWellFormedAndDifferent() {
        SecureRandom random = new SecureRandom();
        String first = PasswordResetCode.generate(random);
        String second = PasswordResetCode.generate(random);

        assertThat(first).hasSize(PasswordResetCode.LENGTH);
        assertThat(PasswordResetCode.isWellFormed(first)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void normalizeAcceptsWhatTheUserTypes() {
        // minuscole, trattino e spazi, lettere ambigue (O → 0, I/L → 1)
        assertThat(PasswordResetCode.normalize("abcdefgh-23456789")).isEqualTo("ABCDEFGH23456789");
        assertThat(PasswordResetCode.normalize(" ABCD EFGH 2345 6789 ")).isEqualTo("ABCDEFGH23456789");
        assertThat(PasswordResetCode.normalize("o1il")).isEqualTo("0111");
    }

    @Test
    void isWellFormedRejectsWrongLengthAndForeignCharacters() {
        assertThat(PasswordResetCode.isWellFormed("ABCDEFGH2345678")).isFalse();
        assertThat(PasswordResetCode.isWellFormed("ABCDEFGH234567890")).isFalse();
        // U non è nell'alfabeto, € nemmeno
        assertThat(PasswordResetCode.isWellFormed("UBCDEFGH23456789")).isFalse();
        assertThat(PasswordResetCode.isWellFormed("€BCDEFGH23456789")).isFalse();
    }

    @Test
    void displaySplitsInTwoHalves() {
        assertThat(PasswordResetCode.display("ABCDEFGH23456789")).isEqualTo("ABCDEFGH-23456789");
        assertThatThrownBy(() -> PasswordResetCode.display("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
