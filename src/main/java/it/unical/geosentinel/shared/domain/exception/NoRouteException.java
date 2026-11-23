// Eccezione del calcolo del percorso tra due punti: non esiste un tragitto sulla rete dei sentieri,
// perché uno dei due punti è a più di 1 km da ogni sentiero oppure i sentieri vicini non sono collegati.
// GlobalExceptionHandler la traduce in un 422 no-route.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che il motore di percorso non ha trovato un tragitto sui sentieri tra i due punti richiesti.
 */
public class NoRouteException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso che spiega le condizioni da rispettare.
     */
    public NoRouteException() {
        super("No route on the trails: both points must be within 1 km of a trail, on trails that connect");
    }
}
