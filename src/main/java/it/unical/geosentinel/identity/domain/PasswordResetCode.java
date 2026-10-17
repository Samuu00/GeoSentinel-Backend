package it.unical.geosentinel.identity.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;

public final class PasswordResetCode {
    static final int LENGTH = 16;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private PasswordResetCode() {}

    public static String generate(SecureRandom random) {
        Objects.requireNonNull(random, "random must not be null");
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public static String normalize(String input){
        Objects.requireNonNull(input, "input must not be null");
        StringBuilder out = new StringBuilder(input.length());
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case ' ', '-' -> { }
                case 'O' -> out.append('0');
                case 'I', 'L' -> out.append('1');
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static boolean isWellFormed(String normalized){
        Objects.requireNonNull(normalized, "normalized cannot be null");
        if (normalized.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    public static String display(String normalized){
        if (!isWellFormed(normalized)) {
            throw new IllegalArgumentException("Malformed reset code");
        }
        return normalized.substring(0, LENGTH / 2) + "-" + normalized.substring(LENGTH / 2);
    }
}
