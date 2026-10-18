package it.unical.geosentinel.identity.domain;

final class Base32 {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private Base32() {}

    // RFC 4648 senza padding: le app di autenticazione lo accettano
    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }
}