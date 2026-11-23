// DTO dell'API REST dei media: la richiesta che avvia un caricamento, la sessione di caricamento, l'URL di
// download e i metadati di un file.
package it.unical.geosentinel.media.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta dei media.
 */
public class MediaDtos {
    /**
     * Corpo della richiesta che avvia un caricamento.
     *
     * @param originalFilename nome del file, al massimo 255 caratteri
     * @param contentType      tipo MIME
     * @param sizeBytes        dimensione in byte
     * @param entityType       tipo dell'entità a cui allegare il file, facoltativo
     * @param entityId         id dell'entità, facoltativo
     */
    public record InitUploadRequest(
            @NotBlank(message = "Original filename is required")
            @Size(max = 255, message = "Filename must not exceed 255 characters")
            String originalFilename,

            // Tipo e dimensione massima li valida MediaPolicy (415 e 413): qui si controlla solo la presenza,
            // per non duplicare le regole.
            @NotBlank(message = "Content-Type is required")
            @Size(max = 100, message = "Content-Type must not exceed 100 characters")
            String contentType,

            @NotNull(message = "File size is required")
            @Positive(message = "File size must be greater than 0 bytes")
            Long sizeBytes,

            // Facoltativi, ma vanno indicati insieme: se ne arriva uno solo la risposta è 422.
            MediaTargetType entityType,

            UUID entityId
    ) {}

    /**
     * Sessione di caricamento. Il client fa un POST multipart verso uploadUrl con tutti i formFields e poi
     * il campo "file". Lo storage rifiuta un file più grande di quanto dichiarato o con un Content-Type
     * diverso; dopo la sua risposta 204 il client chiama la conferma.
     *
     * @param mediaId    id del file
     * @param uploadUrl  indirizzo dello storage
     * @param formFields campi della policy da inviare invariati
     * @param expiresAt  scadenza dell'autorizzazione
     */
    public record UploadSessionResponse(
            @Schema(requiredMode = REQUIRED) UUID mediaId,
            @Schema(requiredMode = REQUIRED) String uploadUrl,
            @Schema(requiredMode = REQUIRED) Map<String, String> formFields,
            @Schema(requiredMode = REQUIRED) Instant expiresAt
    ) {}

    /**
     * URL firmato per il download.
     *
     * @param mediaId     id del file
     * @param downloadUrl URL temporaneo
     */
    public record DownloadUrlResponse(
            @Schema(requiredMode = REQUIRED) UUID mediaId,
            @Schema(requiredMode = REQUIRED) String downloadUrl
    ) {}

    /**
     * Metadati di un file restituiti al client.
     *
     * @param id               identificativo
     * @param originalFilename nome originale
     * @param contentType      tipo MIME
     * @param sizeBytes        dimensione in byte
     * @param status           stato del caricamento
     * @param entityType       tipo dell'entità collegata, può essere {@code null}
     * @param entityId         id dell'entità collegata, può essere {@code null}
     * @param checksum         {@code null} finché il caricamento non è confermato
     * @param credit           autore e licenza di una foto importata da una fonte esterna, da mostrare
     *                         accanto alla foto; {@code null} per quelle caricate dagli utenti
     * @param createdAt        istante di creazione
     */
    public record MediaResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String originalFilename,
            @Schema(requiredMode = REQUIRED) String contentType,
            @Schema(requiredMode = REQUIRED) long sizeBytes,
            @Schema(requiredMode = REQUIRED) MediaStatus status,
            MediaTargetType entityType,
            UUID entityId,
            String checksum,
            String credit,
            @Schema(requiredMode = REQUIRED) Instant createdAt
    ) {}
}
