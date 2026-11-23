/*
 * Dati di una registrazione passati dal controller ad AuthService, già validati dal DTO RegisterRequest.
 */
package it.unical.geosentinel.identity.application;

import java.time.LocalDate;

/**
 * Comando di registrazione di un nuovo escursionista.
 *
 * @param email email dell'utente, non ancora normalizzata
 * @param rawPassword password in chiaro, che il service trasforma in hash
 * @param displayName nome mostrato nell'app
 * @param birthDate data di nascita: serve solo al controllo dell'età e non viene mai salvata nel database
 */
public record RegisterCommand(
        String email,
        String rawPassword,
        String displayName,
        LocalDate birthDate
) {}
