// Test unitario dell'implementazione TOTP (codici a tempo dell'autenticazione a due fattori) e della codifica
// Base32 del segreto. I codici sono confrontati con i vettori di prova ufficiali dell'RFC 6238; non usa Spring.
package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il calcolo dei codici di {@link Totp}, la tolleranza sulla finestra temporale, la codifica
 * {@link Base32} e l'URI {@code otpauth} da mostrare come QR code.
 */
class TotpTest {

    /** Segreto per SHA-1 usato dall'appendice B dell'RFC 6238. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /**
     * Per ogni istante dei vettori di prova dell'RFC 6238 (appendice B) il codice calcolato deve coincidere con
     * quello atteso. L'RFC riporta codici a 8 cifre; qui sono troncati alle ultime 6, quelle che usa l'app.
     *
     * @param epochSecond l'istante in secondi dall'epoca Unix
     * @param expected    il codice atteso a 6 cifre
     */
    @ParameterizedTest
    @CsvSource({
            "59, 287082",
            "1111111109, 081804",
            "1111111111, 050471",
            "1234567890, 005924",
            "2000000000, 279037",
            "20000000000, 353130"
    })
    void matchesRfc6238Vectors(long epochSecond, String expected) {
        long step = Totp.stepAt(Instant.ofEpochSecond(epochSecond));
        assertThat(String.format(Locale.ROOT, "%06d", Totp.codeAt(RFC_SECRET, step))).isEqualTo(expected);
    }

    /** Il passo temporale deve cambiare esattamente ogni 30 secondi: 29 è ancora il passo 0, 30 è già il passo 1. */
    @Test
    void stepChangesExactlyEvery30Seconds() {
        assertThat(Totp.stepAt(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
    }

    /**
     * Per compensare lo scarto tra l'orologio del telefono e quello del server, viene accettato il codice del
     * passo corrente e quelli di un passo prima e un passo dopo, e il metodo restituisce il passo trovato. I
     * codici di due passi prima o dopo devono essere rifiutati, con risultato -1.
     */
    @Test
    void acceptsOneStepEachSideAndNothingMore() {
        Instant now = Instant.ofEpochSecond(1_111_111_111);
        long center = Totp.stepAt(now);
        for (long offset = -1; offset <= 1; offset++) {
            assertThat(Totp.matchingStep(RFC_SECRET, code(center + offset), now)).isEqualTo(center + offset);
        }
        assertThat(Totp.matchingStep(RFC_SECRET, code(center - 2), now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, code(center + 2), now)).isEqualTo(-1);
    }

    /** La codifica Base32 deve seguire l'RFC 4648 senza il riempimento finale con "=". */
    @Test
    void base32FollowsRfc4648WithoutPadding() {
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    /**
     * L'URI {@code otpauth} letto dalle app di autenticazione deve codificare l'account (i caratteri {@code +}
     * e {@code @} diventano {@code %2B} e {@code %40}) e riportare emittente, numero di cifre e periodo.
     */
    @Test
    void otpauthUriEncodesTheAccount() {
        String uri = Totp.otpauthUri("mario+test@example.com", RFC_SECRET);
        assertThat(uri).startsWith("otpauth://totp/GeoSentinel:mario%2Btest%40example.com?secret=")
                .endsWith("&issuer=GeoSentinel&digits=6&period=30");
    }

    /**
     * Calcola il codice a 6 cifre del segreto di prova per un passo temporale.
     *
     * @param step il passo temporale
     * @return il codice, con gli zeri iniziali
     */
    private static String code(long step) {
        return String.format(Locale.ROOT, "%06d", Totp.codeAt(RFC_SECRET, step));
    }
}
