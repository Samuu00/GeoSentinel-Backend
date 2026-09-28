package it.unical.geosentinel.media.web.dto;

import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
            UUID mediaId,
            String uploadUrl,
            Map<String, String> formFields,
            Instant expiresAt
    ) {}

    public record DownloadUrlResponse(
            UUID mediaId,
            String downloadUrl
    ) {}

    public record MediaResponse(
            UUID id,
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaStatus status,
            MediaTargetType entityType,
            UUID entityId,
            String checksum,
            Instant createdAt
    ) {}
}
