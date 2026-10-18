package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {
    private final MfaSecretCipher cipher = new MfaSecretCipher(new byte[32]);
    private final byte[] secret = "segreto-di-20-byte!!".getBytes();

    @Test
    void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void sameSecretGivesDifferentCiphertexts() {
        // IV casuale: due cifrature dello stesso segreto non si riconoscono
        assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
    }

    @Test
    void anyAlteredByteIsAnErrorNeverAWrongSecret() {
        byte[] enc = cipher.encrypt(secret);
        for (int i = 0; i < enc.length; i++) {
            byte[] tampered = Arrays.copyOf(enc, enc.length);
            tampered[i] ^= 1;
            assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anotherKeyCannotRead() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        byte[] enc = cipher.encrypt(secret);
        assertThatThrownBy(() -> new MfaSecretCipher(otherKey).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncatedInputIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[12])).isInstanceOf(IllegalStateException.class);
    }
}
