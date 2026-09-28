package it.unical.geosentinel.media.application.dto;

import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class MediaCommands {
    public record InitUploadCommand(
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaTargetType entityType,
            UUID entityId
    ) {}

    // Il client fa un POST multipart verso uploadUrl con tutti i formFields più il campo "file" (per ultimo)
    public record UploadSessionView(
            UUID mediaId,
            String uploadUrl,
            Map<String, String> formFields,
            Instant expiresAt
    ) {}

    // Niente ownerId: i media allegati li vedono anche gli altri utenti (come reporterId in HazardResponse)
    public record MediaFileView(
            UUID id,
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaStatus status,
            MediaTargetType entityType,
            UUID entityId,
            String checksum,
            Instant createdAt
    ) {
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
                    file.getCreatedAt()
            );
        }
    }
}
