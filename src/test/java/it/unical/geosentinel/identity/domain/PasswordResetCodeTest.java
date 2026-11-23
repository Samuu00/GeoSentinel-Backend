// Test unitario di PasswordResetCode, la classe che genera e normalizza il codice inviato via email per
// reimpostare la password. Il codice è di 16 caratteri presi da un alfabeto senza lettere ambigue e viene
// mostrato all'utente diviso in due metà. Non usa Spring.
package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica generazione, normalizzazione, validazione del formato e visualizzazione del codice di reset.
 */
class PasswordResetCodeTest {

    /**
     * Due codici generati con lo stesso {@link SecureRandom} devono avere la lunghezza prevista, rispettare il
     * formato ed essere diversi tra loro.
     */
    @Test
    void generatedCodesAreWellFormedAndDifferent() {
        SecureRandom random = new SecureRandom();
        String first = PasswordResetCode.generate(random);
        String second = PasswordResetCode.generate(random);

        assertThat(first).hasSize(PasswordResetCode.LENGTH);
        assertThat(PasswordResetCode.isWellFormed(first)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    /**
     * La normalizzazione deve accettare il codice come l'utente lo digita: lettere minuscole, trattino e spazi
     * vengono ricondotti alla forma canonica, e le lettere facili da confondere con le cifre vengono convertite
     * (O diventa 0, I e L diventano 1).
     */
    @Test
    void normalizeAcceptsWhatTheUserTypes() {
        assertThat(PasswordResetCode.normalize("abcdefgh-23456789")).isEqualTo("ABCDEFGH23456789");
        assertThat(PasswordResetCode.normalize(" ABCD EFGH 2345 6789 ")).isEqualTo("ABCDEFGH23456789");
        assertThat(PasswordResetCode.normalize("o1il")).isEqualTo("0111");
    }

    /**
     * Un codice con un carattere in meno o in più, oppure con caratteri fuori dall'alfabeto, non è ben formato.
     */
    @Test
    void isWellFormedRejectsWrongLengthAndForeignCharacters() {
        assertThat(PasswordResetCode.isWellFormed("ABCDEFGH2345678")).isFalse();
        assertThat(PasswordResetCode.isWellFormed("ABCDEFGH234567890")).isFalse();
        // La U è esclusa dall'alfabeto (come I, L e O); il simbolo dell'euro è un carattere estraneo.
        assertThat(PasswordResetCode.isWellFormed("UBCDEFGH23456789")).isFalse();
        assertThat(PasswordResetCode.isWellFormed("€BCDEFGH23456789")).isFalse();
    }

    /**
     * Per la visualizzazione il codice viene diviso in due metà da 8 caratteri separate da un trattino; un
     * codice della lunghezza sbagliata produce una {@link IllegalArgumentException}.
     */
    @Test
    void displaySplitsInTwoHalves() {
        assertThat(PasswordResetCode.display("ABCDEFGH23456789")).isEqualTo("ABCDEFGH-23456789");
        assertThatThrownBy(() -> PasswordResetCode.display("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
