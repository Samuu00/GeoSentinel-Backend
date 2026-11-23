/*
 * Generazione e normalizzazione dei codici per il reset della password inviati via email. Il codice è pensato
 * per essere ricopiato a mano dall'utente, quindi usa un alfabeto senza caratteri che si confondono.
 */
package it.unical.geosentinel.identity.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;

/**
 * Funzioni statiche sul codice di reset della password: 16 caratteri dell'alfabeto Base32 di Crockford
 * (cifre e lettere senza I, L, O e U), mostrati all'utente in due gruppi da 8 separati da un trattino.
 * Le usa {@code PasswordResetService}.
 */
public final class PasswordResetCode {
    /** Numero di caratteri del codice, esclusi i separatori. */
    static final int LENGTH = 16;
    /** Alfabeto di Crockford: niente I, L, O e U per evitare confusioni con 1, 0 e V. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private PasswordResetCode() {}

    /**
     * Genera un codice casuale nella forma normalizzata (senza trattino).
     *
     * @param random generatore crittograficamente sicuro
     * @return un codice di {@link #LENGTH} caratteri
     */
    public static String generate(SecureRandom random) {
        Objects.requireNonNull(random, "random must not be null");
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * Porta un codice digitato dall'utente nella forma canonica: maiuscolo, senza spazi né trattini, con
     * O letta come 0 e I o L lette come 1 (le sostituzioni previste da Crockford). Non controlla la validità,
     * per quella c'è {@link #isWellFormed(String)}.
     *
     * @param input codice così come l'ha scritto l'utente
     * @return il codice normalizzato
     */
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

    /**
     * Controlla che un codice già normalizzato abbia la lunghezza giusta e solo caratteri dell'alfabeto.
     *
     * @param normalized codice normalizzato
     * @return {@code true} se il codice ha un formato valido
     */
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

    /**
     * Formatta il codice per l'email, in due gruppi da 8 caratteri separati da un trattino.
     *
     * @param normalized codice normalizzato
     * @return il codice da mostrare all'utente
     * @throws IllegalArgumentException se il codice non è ben formato
     */
    public static String display(String normalized){
        if (!isWellFormed(normalized)) {
            throw new IllegalArgumentException("Malformed reset code");
        }
        return normalized.substring(0, LENGTH / 2) + "-" + normalized.substring(LENGTH / 2);
    }
}
