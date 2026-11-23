// Eccezione lanciata quando l'object storage (MinIO) non risponde. Sta in shared perché la traduce
// GlobalExceptionHandler, che risponde 503 storage-unavailable con un messaggio generico (quello di
// MinIO potrebbe contenere host e nomi dei bucket) e l'header Retry-After.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala un'indisponibilità temporanea dello storage dei file. Il client può ritentare.
 */
public class StorageUnavailableException extends DomainException {
    /**
     * @param message descrizione interna dell'errore
     * @param cause   eccezione originale del client MinIO
     */
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
