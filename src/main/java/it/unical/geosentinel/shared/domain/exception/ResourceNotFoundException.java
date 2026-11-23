// Eccezione lanciata quando una risorsa richiesta (sentiero, rifugio, media e così via) non esiste o non
// è visibile all'utente. GlobalExceptionHandler la traduce in un 404 not-found.
package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

/**
 * Segnala che la risorsa cercata non è stata trovata.
 */
public class ResourceNotFoundException extends DomainException{
    /**
     * @param message descrizione dell'errore
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Costruisce un messaggio standard con il tipo e l'identificativo della risorsa.
     *
     * @param resourceName nome del tipo di risorsa (ad esempio "Trail")
     * @param resourceId   identificativo cercato
     */
    public ResourceNotFoundException(String resourceName, UUID resourceId) {
        super(String.format("Resource %s with id %s not found", resourceName, resourceId));
    }
}
