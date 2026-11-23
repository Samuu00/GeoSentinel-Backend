// Eccezione lanciata quando un utente prova ad attivare la verifica in due passaggi che ha già attiva.
// GlobalExceptionHandler la traduce in un 409 mfa-already-enabled.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che la verifica in due passaggi è già attiva sull'account.
 */
public class MfaAlreadyEnabledException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso.
     */
    public MfaAlreadyEnabledException() {
        super("Two-factor authentication is already enabled");
    }
}
