/*
 * Evento pubblicato quando un amministratore azzera la verifica in due passaggi di un utente.
 * MfaAlertMailListener lo riceve dopo il commit e avvisa l'utente via email.
 */
package it.unical.geosentinel.identity.application;

/**
 * Richiesta di un'email che informa dell'azzeramento della verifica in due passaggi.
 *
 * @param email destinatario dell'avviso
 */
public record MfaAdminResetRequested(String email) {
    /** Nasconde l'email, così l'evento non la scrive mai nei log. */
    @Override
    public String toString() {
        return "MfaAdminResetRequested[email=***]";
    }
}
