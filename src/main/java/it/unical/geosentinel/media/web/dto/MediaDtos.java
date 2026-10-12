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

public class MediaDtos {
    public record InitUploadRequest(
            @NotBlank(message = "Original filename is required")
            @Size(max = 255, message = "Filename must not exceed 255 characters")
            String originalFilename,

            // Tipo e dimensione massima li valida MediaPolicy (415 / 413): qui solo la presenza, per non duplicare le regole
            @NotBlank(message = "Content-Type is required")
            @Size(max = 100, message = "Content-Type must not exceed 100 characters")
            String contentType,

            @NotNull(message = "File size is required")
            @Positive(message = "File size must be greater than 0 bytes")
            Long sizeBytes,

            // Facoltativi, ma insieme (422 se ne arriva uno solo)
            MediaTargetType entityType,

            UUID entityId
    ) {}

    // POST multipart verso uploadUrl: tutti i formFields, poi il campo "file". MinIO rifiuta un file più grande del
    // dichiarato o con un Content-Type diverso; dopo il 204 di MinIO il client chiama /{mediaId}/confirm
    public record UploadSessionResponse(
            @Schema(requiredMode = REQUIRED) UUID mediaId,
            @Schema(requiredMode = REQUIRED) String uploadUrl,
            @Schema(requiredMode = REQUIRED) Map<String, String> formFields,
            @Schema(requiredMode = REQUIRED) Instant expiresAt
    ) {}

    public record DownloadUrlResponse(
            @Schema(requiredMode = REQUIRED) UUID mediaId,
            @Schema(requiredMode = REQUIRED) String downloadUrl
    ) {}

    // checksum: null finché l'upload non è confermato. credit: autore e licenza di una foto importata da una fonte
    // esterna, da mostrare accanto alla foto; null per quelle caricate dagli utenti
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
