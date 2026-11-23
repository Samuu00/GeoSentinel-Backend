// Eccezione lanciata al primo accesso con Google quando la richiesta non contiene la data di nascita,
// necessaria per il controllo dell'età. La risposta è un 422 birth-date-required: l'app chiede la data
// all'utente e ripete la stessa richiesta con lo stesso ID token.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che per creare l'account con Google serve la data di nascita dell'utente.
 * Viene tradotta in 422 {@code birth-date-required}.
 */
public class BirthDateRequiredException extends DomainException {
    /**
     * @param message descrizione dell'errore, riportata nel campo {@code detail} della risposta
     */
    public BirthDateRequiredException(String message) {
        super(message);
    }
}
