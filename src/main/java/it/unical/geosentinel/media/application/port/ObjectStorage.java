// Porta (interfaccia) verso l'object storage in cui finiscono i file degli utenti. Il service dei media
// dipende solo da questa interfaccia; l'implementazione su MinIO sta nel package infrastructure. Il
// bucket è un dettaglio dell'implementazione: il service lavora solo con le object key.
package it.unical.geosentinel.media.application.port;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Operazioni sullo storage degli oggetti usate dalla feature media.
 */
public interface ObjectStorage {
    /**
     * Genera una presigned POST policy per il caricamento diretto dal client: un form multipart verso
     * l'URL restituito, con tutti i campi della policy più il campo {@code file}. A differenza di un PUT
     * pre-firmato, con la policy è lo storage stesso a rifiutare un file più grande di {@code maxBytes} o
     * con un Content-Type diverso da quello dichiarato.
     * <p>
     * Il client scrive su una copia di staging e non su {@code objectKey}: la policy resta valida fino alla
     * scadenza, e con la stessa policy si potrebbe altrimenti sostituire un file già verificato.
     * Sull'oggetto finale scrive solo {@link #promoteUpload}.
     *
     * @param objectKey   chiave dell'oggetto finale
     * @param contentType tipo MIME che il client deve usare
     * @param maxBytes    dimensione massima ammessa
     * @param expiration  durata della policy
     * @return URL e campi del form da inviare al client
     */
    PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Duration expiration);

    /**
     * Copia il caricamento di staging su {@code objectKey} e cancella lo staging. L'operazione si può
     * ripetere: se lo staging non c'è più restituisce l'oggetto finale, se esiste. Il controllo del
     * contenuto va fatto dopo, sull'oggetto finale, che il client non può più modificare.
     *
     * @param objectKey chiave dell'oggetto finale
     * @return i metadati dell'oggetto finale, oppure vuoto se il client non ha caricato nulla
     */
    Optional<StoredObject> promoteUpload(String objectKey);

    /**
     * Genera un URL pre-firmato e temporaneo per scaricare l'oggetto con una GET. La risposta dello storage
     * avrà il Content-Type verificato e {@code Content-Disposition: attachment}, così un browser non
     * esegue il contenuto inline.
     *
     * @param objectKey   chiave dell'oggetto
     * @param contentType tipo MIME da imporre nella risposta
     * @param expiration  validità dell'URL
     * @return l'URL firmato
     */
    String generatePresignedDownloadUrl(String objectKey, String contentType, Duration expiration);

    /**
     * @param objectKey chiave dell'oggetto
     * @return i metadati dell'oggetto, oppure vuoto se non esiste (caricamento non ancora avvenuto)
     */
    Optional<StoredObject> stat(String objectKey);

    /**
     * Legge l'inizio dell'oggetto, per il controllo della firma del formato (magic bytes).
     *
     * @param objectKey chiave dell'oggetto
     * @param length    numero di byte da leggere
     * @return i primi byte, al massimo {@code length}
     */
    byte[] readHead(String objectKey, int length);

    /**
     * Elimina definitivamente l'oggetto e la sua copia di staging; non dà errore se non esistono.
     *
     * @param objectKey chiave dell'oggetto
     */
    void deleteObject(String objectKey);

    /**
     * Metadati di un oggetto salvato.
     *
     * @param sizeBytes dimensione in byte
     * @param etag      ETag calcolato dallo storage
     */
    record StoredObject(long sizeBytes, String etag) {}

    /**
     * Autorizzazione al caricamento diretto.
     *
     * @param url        indirizzo a cui inviare il form
     * @param formFields campi della policy da inviare invariati
     */
    record PresignedUpload(String url, Map<String, String> formFields) {}
}
