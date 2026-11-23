/*
 * Evento pubblicato quando una richiesta di reset della password supera il limite dei tentativi. Lo riceve
 * PasswordResetMailListener, che genera e spedisce il codice in background.
 */
package it.unical.geosentinel.identity.application;

/**
 * Richiesta di reset accettata. Non contiene il codice, che viene generato dal listener.
 *
 * @param normalizedEmail email già normalizzata indicata dall'utente
 */
public record PasswordResetRequested(String normalizedEmail) {
    /** Nasconde l'email, così l'evento non la scrive mai nei log. */
    @Override
    public String toString() {
        return "PasswordResetRequested[email=***]";
    }
}
