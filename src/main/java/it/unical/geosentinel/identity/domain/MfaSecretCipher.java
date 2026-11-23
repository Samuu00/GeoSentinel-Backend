/*
 * Cifratura dei segreti TOTP salvati nel database. Il segreto non è mai memorizzato in chiaro: si cifra con
 * una chiave AES letta dalla configurazione, così un dump del database da solo non basta a generare i codici.
 */
package it.unical.geosentinel.identity.domain;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Cifra e decifra i segreti TOTP con AES-256-GCM. Il formato salvato è l'IV (12 byte) seguito dal testo
 * cifrato con il tag di autenticazione in coda. Viene creata come bean in {@code MfaConfig}.
 */
public final class MfaSecretCipher {
    /** Lunghezza dell'IV in byte, quella raccomandata per GCM. */
    private static final int IV_BYTES = 12;
    /** Lunghezza del tag di autenticazione GCM in bit. */
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param key32 chiave AES di 32 byte (AES-256)
     */
    public MfaSecretCipher(byte[] key32) {
        this.key = new SecretKeySpec(key32, "AES");
    }

    /**
     * Cifra un segreto con un IV casuale nuovo a ogni chiamata (in GCM riusare un IV con la stessa chiave
     * comprometterebbe la cifratura).
     *
     * @param plain segreto in chiaro
     * @return IV seguito dal testo cifrato con tag
     * @throws IllegalStateException se la cifratura fallisce
     */
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

    /**
     * Decifra un segreto prodotto da {@link #encrypt(byte[])}.
     *
     * @param enc IV seguito dal testo cifrato con tag
     * @return il segreto in chiaro
     * @throws IllegalStateException se il dato è troncato o alterato, oppure se la chiave non è quella giusta
     */
    public byte[] decrypt(byte[] enc) {
        // Un dato non più lungo dell'IV non può essere un nostro testo cifrato: si fallisce prima del Cipher
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
            // Un tag non valido significa dato alterato o chiave cambiata: è un errore di sistema,
            // non va mai trattato come un codice sbagliato dell'utente
            throw new IllegalStateException("Segreto MFA non leggibile", e);
        }
    }

    /**
     * Crea un {@link Cipher} AES/GCM inizializzato con la chiave e l'IV dati.
     *
     * @param mode {@link Cipher#ENCRYPT_MODE} o {@link Cipher#DECRYPT_MODE}
     * @param iv vettore di inizializzazione
     * @return il cipher pronto all'uso
     * @throws GeneralSecurityException se l'algoritmo non è disponibile o i parametri non sono validi
     */
    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return c;
    }
}
