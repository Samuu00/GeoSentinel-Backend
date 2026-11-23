// Eccezione lanciata quando il motore di percorso esterno (il servizio GraphHopper) non risponde o
// risponde con un errore inatteso. Sta in shared perché la traduce GlobalExceptionHandler, che risponde
// 503 routing-unavailable con l'header Retry-After.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala un'indisponibilità temporanea del servizio di calcolo dei percorsi. Il client può ritentare.
 */
public class RoutingUnavailableException extends DomainException {
    /**
     * @param message descrizione interna dell'errore (al client va un messaggio generico)
     * @param cause   errore originale del client HTTP
     */
    public RoutingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
