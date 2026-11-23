// Eccezione legata all'header Idempotency-Key, con cui l'app rende sicuro il ritentativo delle richieste
// che creano dati. Se arriva una chiave già vista ma con un body diverso non si tratta di un ritentativo:
// il client ha riusato la chiave per errore, e la risposta è un 422 idempotency-key-reused.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che la stessa {@code Idempotency-Key} è stata usata per due richieste con contenuto diverso.
 */
public class IdempotencyKeyReusedException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso.
     */
    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different request");
    }
}
