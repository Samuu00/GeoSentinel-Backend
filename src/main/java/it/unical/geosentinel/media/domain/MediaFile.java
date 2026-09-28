package it.unical.geosentinel.media.domain;

import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "media_files")
public class MediaFile extends AuditableEntity {
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 64)
    private String objectKey;
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;
    // Dichiarata dal client finché il file è PENDING_UPLOAD, quella reale dopo il confirm
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MediaStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", length = 20)
    private MediaTargetType entityType;
    @Column(name = "entity_id")
    private UUID entityId;
    @Column(name = "checksum", length = 64)
    private String checksum;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected MediaFile() {}

    private MediaFile(
            UUID ownerId,
            String objectKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaTargetType entityType,
            UUID entityId
    ) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId cannot be null");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey cannot be null");
        this.originalFilename = Objects.requireNonNull(originalFilename, "originalFilename cannot be null");
        this.contentType = Objects.requireNonNull(contentType, "contentType cannot be null");
        this.sizeBytes = sizeBytes;
        this.status = MediaStatus.PENDING_UPLOAD;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public static MediaFile initUpload(
            UUID ownerId,
            String objectKey,
            String originalFilename,
            String contentType,
            long sizeBytes,
            MediaTargetType entityType,
            UUID entityId
    ) {
        MediaPolicy.validateImageUpload(contentType, sizeBytes);
        if ((entityType == null) != (entityId == null)) {
            throw new BusinessRuleViolationException("entityType and entityId must be provided together");
        }
        return new MediaFile(ownerId, objectKey, originalFilename, contentType, sizeBytes, entityType, entityId);
    }

    public void markReady(long actualSizeBytes, String checksum) {
        requirePending(MediaStatus.READY);
        this.status = MediaStatus.READY;
        this.sizeBytes = actualSizeBytes;
        this.checksum = checksum;
    }

    public void quarantine() {
        requirePending(MediaStatus.QUARANTINED);
        this.status = MediaStatus.QUARANTINED;
    }

    private void requirePending(MediaStatus target) {
        if (this.status != MediaStatus.PENDING_UPLOAD) {
            throw new BusinessRuleViolationException("Cannot mark as %s a file with status %s".formatted(target, this.status));
        }
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public UUID getOwnerId() { return ownerId; }
    public String getObjectKey() { return objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public MediaStatus getStatus() { return status; }
    public MediaTargetType getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getChecksum() { return checksum; }
    public Long getVersion() { return version; }
}
