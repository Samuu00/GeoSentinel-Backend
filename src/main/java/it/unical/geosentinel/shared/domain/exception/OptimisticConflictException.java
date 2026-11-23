// Eccezione del lock ottimistico: la risorsa è stata modificata da un'altra richiesta dopo che il client
// l'ha letta (la versione inviata con If-Match non coincide con quella attuale). Il nome evita lo scontro
// con java.util.ConcurrentModificationException. La risposta è un 409 concurrent-modification.
package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

/**
 * Segnala un conflitto di versione su una risorsa protetta da lock ottimistico: il client deve
 * rileggerla e ripetere la modifica.
 */
public class OptimisticConflictException extends DomainException{
    /**
     * @param message descrizione dell'errore
     */
    public OptimisticConflictException(String message) {
        super(message);
    }

    /**
     * Costruisce il messaggio a partire dalle due versioni a confronto.
     *
     * @param resourceName     nome del tipo di risorsa (ad esempio "Ticket")
     * @param resourceId       identificativo della risorsa
     * @param excpectedVersion versione che il client si aspettava
     * @param currentVersion   versione presente nel database
     */
    public OptimisticConflictException(String resourceName, UUID resourceId, long excpectedVersion, long currentVersion) {
        super(String.format("Resource %s with id %s has been modified since last read. Expected version %d, current version %d",
                resourceName, resourceId, excpectedVersion, currentVersion));
    }
}
