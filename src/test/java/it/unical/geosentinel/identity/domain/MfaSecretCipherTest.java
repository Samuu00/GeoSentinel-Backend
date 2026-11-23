// Test unitario di MfaSecretCipher, il componente che cifra il segreto TOTP dell'autenticazione a due fattori
// prima di salvarlo nel database (cifratura autenticata con IV casuale). Usa una chiave di 32 byte a zero,
// valida solo per i test, e non avvia Spring.
package it.unical.geosentinel.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica che la cifratura del segreto MFA sia reversibile solo con la chiave giusta e che qualsiasi
 * alterazione del testo cifrato venga rilevata.
 */
class MfaSecretCipherTest {

    /** Cifratore con una chiave di prova da 32 byte tutti a zero. */
    private final MfaSecretCipher cipher = new MfaSecretCipher(new byte[32]);

    /** Segreto di 20 byte, la stessa lunghezza di un segreto TOTP reale. */
    private final byte[] secret = "segreto-di-20-byte!!".getBytes();

    /** Cifrando e poi decifrando si deve riottenere esattamente il segreto di partenza. */
    @Test
    void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    /**
     * Due cifrature dello stesso segreto devono dare testi cifrati diversi: l'IV è casuale, quindi chi legge
     * il database non può capire se due utenti hanno lo stesso segreto.
     */
    @Test
    void sameSecretGivesDifferentCiphertexts() {
        assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
    }

    /**
     * Cambia un bit alla volta in ogni posizione del testo cifrato (IV, dati e tag). In ogni caso la
     * decifratura deve fallire con una {@link IllegalStateException}, mai restituire un segreto sbagliato.
     */
    @Test
    void anyAlteredByteIsAnErrorNeverAWrongSecret() {
        byte[] enc = cipher.encrypt(secret);
        for (int i = 0; i < enc.length; i++) {
            byte[] tampered = Arrays.copyOf(enc, enc.length);
            tampered[i] ^= 1;
            assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        }
    }

    /** Un cifratore con una chiave diversa non deve riuscire a decifrare: ci si aspetta una {@link IllegalStateException}. */
    @Test
    void anotherKeyCannotRead() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        byte[] enc = cipher.encrypt(secret);
        assertThatThrownBy(() -> new MfaSecretCipher(otherKey).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    /** Un input troppo corto per contenere IV e tag deve essere rifiutato con una {@link IllegalStateException}. */
    @Test
    void truncatedInputIsRejected() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[12])).isInstanceOf(IllegalStateException.class);
    }
}
