package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {
    // Segreto SHA-1 dell'RFC 6238, appendice B
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    // Vettori dell'appendice B (8 cifre) troncati alle ultime 6
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

    @Test
    void stepChangesExactlyEvery30Seconds() {
        assertThat(Totp.stepAt(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
    }

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

    @Test
    void base32FollowsRfc4648WithoutPadding() {
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void otpauthUriEncodesTheAccount() {
        String uri = Totp.otpauthUri("mario+test@example.com", RFC_SECRET);
        assertThat(uri).startsWith("otpauth://totp/GeoSentinel:mario%2Btest%40example.com?secret=")
                .endsWith("&issuer=GeoSentinel&digits=6&period=30");
    }

    private static String code(long step) {
        return String.format(Locale.ROOT, "%06d", Totp.codeAt(RFC_SECRET, step));
    }
}
