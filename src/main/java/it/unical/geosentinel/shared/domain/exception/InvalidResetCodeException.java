// Eccezione del reset della password con codice via email. Copre tutti i casi di rifiuto (codice
// sbagliato, scaduto, già usato o tentativi esauriti) con un unico errore, così dalla risposta non si
// capisce quale dei casi si è verificato. Diventa un 422 invalid-reset-code.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Codice di reset della password non valido o non più utilizzabile.
 */
public class InvalidResetCodeException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso che non distingue il motivo del rifiuto.
     */
    public InvalidResetCodeException() {
        super("Invalid or expired reset code");
    }
}
