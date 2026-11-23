// Eccezione di dominio per gli account del personale (gestori, soccorritori, amministratori).
// Questi account appartengono all'organizzazione del parco e non possono essere cancellati dall'utente
// tramite l'app: GlobalExceptionHandler la traduce in una risposta 409 con slug account-managed.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che l'operazione richiesta non è permessa perché l'account non è di un escursionista
 * ({@code HIKER}) ma è gestito dall'organizzazione. Viene tradotta in 409 {@code account-managed}.
 */
public class AccountManagedException extends DomainException {
    /**
     * @param message descrizione dell'errore, riportata nel campo {@code detail} della risposta
     */
    public AccountManagedException(String message) {
        super(message);
    }
}
