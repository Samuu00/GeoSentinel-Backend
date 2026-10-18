package it.unical.geosentinel.identity.domain;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

// AES-256-GCM: formato = IV (12 byte) + testo cifrato con tag
public final class MfaSecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(byte[] key32) {
        this.key = new SecretKeySpec(key32, "AES");
    }

    public byte[] encrypt(byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            byte[] ct = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cifratura MFA fallita", e);
        }
    }

    public byte[] decrypt(byte[] enc) {
        // Più corto dell'IV: non è un testo cifrato nostro, errore prima del Cipher
        if (enc.length <= IV_BYTES) {
            throw new IllegalStateException("Segreto MFA troncato");
        }
        ByteBuffer buf = ByteBuffer.wrap(enc);
        byte[] iv = new byte[IV_BYTES];
        buf.get(iv);
        byte[] ct = new byte[buf.remaining()];
        buf.get(ct);
        try {
            return cipher(Cipher.DECRYPT_MODE, iv).doFinal(ct);
        } catch (GeneralSecurityException e) {
            // Tag non valido = dato alterato: errore di sistema, mai un segreto sbagliato
            throw new IllegalStateException("Segreto MFA non leggibile", e);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return c;
    }
}