/*
 * Evento pubblicato all'avvio della configurazione della verifica in due passaggi: porta il codice di conferma
 * da spedire all'email dell'utente, che MfaAlertMailListener invia dopo il commit.
 */
package it.unical.geosentinel.identity.application;

import java.time.Duration;

/**
 * Richiesta di invio del codice di conferma della configurazione.
 *
 * @param email destinatario
 * @param code codice in chiaro, già formattato per l'email
 * @param validFor durata di validità del codice, da indicare nel testo
 */
public record MfaSetupCodeRequested(String email, String code, Duration validFor) {
    /** Nasconde email e codice, così nessuno dei due finisce nei log. */
    @Override
    public String toString() {
        return "MfaSetupCodeRequested[email=***, code=***]";
    }
}
