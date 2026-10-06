package it.unical.geosentinel.media.application.port;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

// Il bucket è un dettaglio dell'adapter: il service lavora solo con le object key
public interface ObjectStorage {
    /**
     * Presigned POST policy per il caricamento diretto dal client (form multipart verso {@code url} con i {@code fields}
     * più il campo {@code file}). A differenza di un PUT pre-firmato, è MinIO a rifiutare un file oltre {@code maxBytes}
     * o con un Content-Type diverso da quello dichiarato.
     * <p>
     * Il client scrive su una copia di staging, non su {@code objectKey}: la policy resta valida fino alla scadenza e
     * con la stessa policy si potrebbe sostituire un file già verificato. Sull'oggetto finale scrive solo
     * {@link #promoteUpload}.
     */
    PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Duration expiration);

    /**
     * Copia l'upload di staging su {@code objectKey} e cancella lo staging; restituisce i metadati dell'oggetto finale,
     * vuoto se il client non ha caricato nulla. Ripetibile: senza staging restituisce l'oggetto finale, se esiste.
     * Il controllo del contenuto va fatto dopo, sull'oggetto finale, che il client non può più modificare.
     */
    Optional<StoredObject> promoteUpload(String objectKey);

    /**
     * Genera un URL pre-firmato per il download HTTP GET temporaneo. La risposta di MinIO avrà il
     * Content-Type validato e Content-Disposition: attachment (niente contenuto eseguito inline dal browser).
     */
    String generatePresignedDownloadUrl(String objectKey, String contentType, Duration expiration);

    /**
     * Metadati dell'oggetto, vuoto se non esiste (upload non ancora fatto).
     */
    Optional<StoredObject> stat(String objectKey);

    /**
     * Primi {@code length} byte dell'oggetto, per il controllo dei magic bytes.
     */
    byte[] readHead(String objectKey, int length);

    /**
     * Elimina fisicamente l'oggetto e la sua copia di staging (nessun errore se non esistono).
     */
    void deleteObject(String objectKey);

    record StoredObject(long sizeBytes, String etag) {}

    record PresignedUpload(String url, Map<String, String> formFields) {}
}
