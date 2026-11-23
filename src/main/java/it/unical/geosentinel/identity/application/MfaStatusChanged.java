/*
 * Evento pubblicato quando l'utente attiva o disattiva la verifica in due passaggi. L'email parte dopo il
 * commit tramite MfaAlertMailListener: se chi ha rubato un access token la attiva per chiudere fuori il
 * titolare, il titolare lo viene a sapere subito.
 */
package it.unical.geosentinel.identity.application;

/**
 * Cambio di stato della verifica in due passaggi.
 *
 * @param email destinatario dell'avviso
 * @param enabled {@code true} se è stata attivata, {@code false} se è stata disattivata
 */
public record MfaStatusChanged(String email, boolean enabled) {
    /** Nasconde l'email, così l'evento non la scrive mai nei log. */
    @Override
    public String toString() {
        return "MfaStatusChanged[email=***, enabled=" + enabled + "]";
    }
}
