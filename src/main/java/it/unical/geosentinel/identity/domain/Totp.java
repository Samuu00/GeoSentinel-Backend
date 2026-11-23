/*
 * Implementazione del TOTP (RFC 6238) usato per la verifica in due passaggi: calcolo dei codici a 6 cifre,
 * URI otpauth da mostrare all'utente e verifica di un codice con una piccola tolleranza sull'orologio.
 */
package it.unical.geosentinel.identity.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;

/**
 * Funzioni statiche per i codici TOTP (HMAC-SHA1, passi da 30 secondi, 6 cifre), compatibili con le comuni
 * app di autenticazione. Le usa {@code MfaService} per attivare la verifica in due passaggi e controllare i codici.
 */
public final class Totp {
    /** Durata di un passo TOTP in secondi. */
    static final long STEP_SECONDS = 30;
    /** 10^6: il codice è il resto della divisione per questo valore, quindi ha 6 cifre. */
    private static final int MODULO = 1_000_000;
    /** Numero di passi accettati prima e dopo quello attuale, per tollerare un orologio un po' sfasato. */
    private static final int WINDOW = 1;

    private Totp() {}

    /**
     * Calcola il codice TOTP di un passo con il troncamento dinamico dell'RFC 4226. È pubblico perché lo usano
     * i test (vettori dell'RFC e codici calcolati nei test di integrazione).
     *
     * @param secret segreto condiviso in chiaro
     * @param step numero del passo (secondi Unix diviso {@link #STEP_SECONDS})
     * @return il codice come intero tra 0 e 999999 (senza zeri iniziali)
     * @throws IllegalStateException se la JVM non offre HmacSHA1
     */
    public static int codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            // Troncamento dinamico: gli ultimi 4 bit scelgono da dove leggere 31 bit dell'HMAC
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return binary % MODULO;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 non disponibile", e);
        }
    }

    /**
     * Calcola il passo TOTP in cui cade un istante.
     *
     * @param instant istante da convertire
     * @return il numero del passo
     */
    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /**
     * Codifica il segreto in Base32, il formato che l'utente digita a mano nell'app di autenticazione.
     *
     * @param secret segreto in chiaro
     * @return il segreto in Base32
     */
    public static String encodeSecret(byte[] secret) {
        return Base32.encode(secret);
    }

    /**
     * Costruisce l'URI {@code otpauth://} che l'app di autenticazione legge. Il QR code lo genera il client
     * a partire da questo URI, il backend non produce immagini.
     *
     * @param account nome dell'account mostrato nell'app (l'email dell'utente)
     * @param secret segreto in chiaro
     * @return l'URI con emittente, cifre e periodo
     */
    public static String otpauthUri(String account, byte[] secret) {
        return "otpauth://totp/GeoSentinel:" + URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20")
                + "?secret=" + encodeSecret(secret) + "&issuer=GeoSentinel&digits=6&period=30";
    }

    /**
     * Cerca, nella finestra di passi attorno a {@code now}, quello il cui codice coincide con quello dato.
     * Il codice deve essere già di 6 cifre (lo controlla il service). Il ciclo scorre sempre tutta la finestra
     * e confronta in tempo costante, così il tempo di risposta non rivela quale passo ha combaciato.
     *
     * @param secret segreto in chiaro
     * @param code codice di 6 cifre inserito dall'utente
     * @param now istante attuale
     * @return il passo che combacia, oppure -1 se nessuno combacia
     */
    public static long matchingStep(byte[] secret, String code, Instant now) {
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long center = stepAt(now);
        long matched = -1;
        for (long step = center - WINDOW; step <= center + WINDOW; step++) {
            byte[] expected = String.format(Locale.ROOT, "%06d", codeAt(secret, step)).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given)) {
                matched = step;
            }
        }
        return matched;
    }
}
