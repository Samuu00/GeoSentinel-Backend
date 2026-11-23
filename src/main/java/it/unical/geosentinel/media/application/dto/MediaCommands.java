// Oggetti di trasferimento tra controller e service per i media: il comando che avvia un caricamento,
// la sessione di caricamento restituita al client e la vista dei metadati di un file.
package it.unical.geosentinel.media.application.dto;

import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Contenitore dei record usati dal service dei media.
 */
public class MediaCommands {
    /**
     * Dati per avviare un caricamento.
     *
     * @param originalFilename nome originale del file
     * @param contentType      tipo MIME dichiarato
     * @param sizeBytes        dimensione dichiarata, in byte
     * @param entityType       tipo dell'entità a cui allegarlo, facoltativo
     * @param entityId         id dell'entità, facoltativo
     */
    public record InitUploadCommand(
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaTargetType entityType,
            UUID entityId
    ) {}

    /**
     * Sessione di caricamento: il client fa un POST multipart verso uploadUrl con tutti i formFields più il
     * campo "file", che va per ultimo.
     *
     * @param mediaId    id del file, da usare per la conferma
     * @param uploadUrl  indirizzo dello storage
     * @param formFields campi della policy
     * @param expiresAt  scadenza dell'autorizzazione
     */
    public record UploadSessionView(
            UUID mediaId,
            String uploadUrl,
            Map<String, String> formFields,
            Instant expiresAt
    ) {}

    /**
     * Metadati di un file. Non contiene il proprietario, perché i media allegati li vedono anche gli altri
     * utenti, come per l'autore nelle risposte delle segnalazioni.
     *
     * @param id               identificativo
     * @param originalFilename nome originale
     * @param contentType      tipo MIME
     * @param sizeBytes        dimensione in byte
     * @param status           stato del caricamento
     * @param entityType       tipo dell'entità collegata, può essere {@code null}
     * @param entityId         id dell'entità collegata, può essere {@code null}
     * @param checksum         ETag dello storage, presente dopo la conferma
     * @param credit           autore e licenza per le foto importate, altrimenti {@code null}
     * @param createdAt        istante di creazione
     */
    public record MediaFileView(
            UUID id,
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaStatus status,
            MediaTargetType entityType,
            UUID entityId,
            String checksum,
            String credit,
            Instant createdAt
    ) {
        /**
         * @param file entità del file
         * @return la vista
         */
        public static MediaFileView from(MediaFile file) {
            return new MediaFileView(
                    file.getId(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    file.getStatus(),
                    file.getEntityType(),
                    file.getEntityId(),
                    file.getChecksum(),
                    file.getCredit(),
                    file.getCreatedAt()
            );
        }
    }
}
