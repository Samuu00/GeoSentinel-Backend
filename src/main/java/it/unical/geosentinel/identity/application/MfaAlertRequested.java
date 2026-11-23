/*
 * Evento pubblicato da MfaService al terzo codice sbagliato al login con la verifica in due passaggi attiva.
 * MfaAlertMailListener lo riceve dopo il commit e avvisa il titolare via email.
 */
package it.unical.geosentinel.identity.application;

/**
 * Richiesta di un'email d'avviso per codici di verifica sbagliati.
 *
 * @param email destinatario dell'avviso
 */
public record MfaAlertRequested(String email) {
    /** Nasconde l'email, così l'evento non la scrive mai nei log. */
    @Override
    public String toString() {
        return "MfaAlertRequested[email=***]";
    }
}
