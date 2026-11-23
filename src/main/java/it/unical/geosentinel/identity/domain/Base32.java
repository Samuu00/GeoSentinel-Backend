/*
 * Codifica Base32 usata per mostrare il segreto TOTP nel formato che le app di autenticazione
 * (Google Authenticator, Aegis e simili) si aspettano.
 */
package it.unical.geosentinel.identity.domain;

/**
 * Codificatore Base32 minimale (solo codifica) secondo l'RFC 4648.
 */
final class Base32 {
    /** Alfabeto Base32 standard dell'RFC 4648. */
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private Base32() {}

    /**
     * Codifica i byte in Base32 senza il padding finale con '=', che le app di autenticazione accettano
     * comunque e che rende il segreto più corto da digitare.
     *
     * @param data byte da codificare
     * @return la stringa Base32 in maiuscolo
     */
    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            // Ogni 5 bit accumulati diventano un carattere dell'alfabeto
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        // Bit rimasti: si completano a destra con zeri fino a 5
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }
}
