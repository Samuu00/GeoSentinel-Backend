// Eccezione del login: la password è corretta e l'account ha la verifica in due passaggi attiva, ma la
// richiesta non contiene il codice. La risposta è un 401 mfa-required: il client chiede il codice
// all'utente e rimanda la stessa richiesta con il codice.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che per completare il login serve il codice della verifica in due passaggi.
 */
public class MfaRequiredException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso.
     */
    public MfaRequiredException() {
        super("Two-factor code required");
    }
}
