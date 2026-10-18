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

public final class Totp {
    static final long STEP_SECONDS = 30;
    private static final int MODULO = 1_000_000;
    private static final int WINDOW = 1; // passi accettati prima e dopo quello attuale

    private Totp() {}

    // Pubblici per i test (vettori dell'RFC, codici calcolati negli IT)
    public static int codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
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

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    // Segreto come lo mostra l'app di autenticazione, per l'inserimento a mano
    public static String encodeSecret(byte[] secret) {
        return Base32.encode(secret);
    }

    // URI che l'app legge (o che il client trasforma in QR). Il QR lo fa il client, non il backend
    public static String otpauthUri(String account, byte[] secret) {
        return "otpauth://totp/GeoSentinel:" + URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20")
                + "?secret=" + encodeSecret(secret) + "&issuer=GeoSentinel&digits=6&period=30";
    }

    // Passo della finestra attorno a `now` in cui il codice combacia, altrimenti -1.
    // Il codice deve essere già di 6 cifre (lo controlla il service). Scorre sempre tutta la finestra e confronta
    // in tempo costante: il tempo di risposta non dice quale passo ha combaciato
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