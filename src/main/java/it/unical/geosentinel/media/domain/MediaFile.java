// Entità JPA dei metadati di un file caricato (tabella media_files). Il contenuto vero sta nell'object
// storage MinIO sotto objectKey; qui si salvano proprietario, tipo, dimensione, stato del caricamento ed
// eventuale entità a cui il file è allegato.
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

/**
 * Metadati di un file. Nasce in {@code PENDING_UPLOAD} con {@link #initUpload} e passa a {@code READY}
 * o a {@code QUARANTINED} quando il client conferma il caricamento.
 */
@Entity
@Table(name = "media_files")
public class MediaFile extends AuditableEntity {
    /** Utente che ha caricato il file. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    /** Chiave dell'oggetto nel bucket, generata dal server e quindi non controllabile dal client. */
    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 64)
    private String objectKey;
    /** Nome originale del file sul dispositivo dell'utente. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    /** Tipo MIME, tra quelli ammessi da MediaPolicy. */
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;
    /** Dimensione in byte: quella dichiarata dal client finché il file è in attesa, quella reale dopo la conferma. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Enumerated(EnumType.STRING)
    /** Stato del caricamento. */
    @Column(name = "status", nullable = false, length = 20)
    private MediaStatus status;
    @Enumerated(EnumType.STRING)
    /** Tipo dell'entità a cui il file è allegato; {@code null} insieme a entityId se non è allegato. */
    @Column(name = "entity_type", length = 20)
    private MediaTargetType entityType;
    /** Id dell'entità a cui il file è allegato. */
    @Column(name = "entity_id")
    private UUID entityId;
    /** ETag dell'oggetto restituito dallo storage dopo la conferma. */
    @Column(name = "checksum", length = 64)
    private String checksum;
    /**
     * Autore e licenza di una foto importata da una fonte esterna; {@code null} per le foto caricate dagli
     * utenti. Non si può impostare dall'API, si legge soltanto.
     */
    @Column(name = "credit", length = 300)
    private String credit;

    /** Versione per il lock ottimistico: una conferma e una cancellazione concorrenti non si sovrascrivono. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Costruttore richiesto da JPA. */
    protected MediaFile() {}

    /**
     * Costruttore usato da {@link #initUpload}: crea il file nello stato {@code PENDING_UPLOAD}.
     *
     * @throws NullPointerException se manca un campo obbligatorio
     */
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

    /**
     * Crea i metadati di un nuovo caricamento dopo aver validato tipo e dimensione.
     *
     * @param ownerId          utente che carica
     * @param objectKey        chiave dell'oggetto nel bucket
     * @param originalFilename nome originale del file
     * @param contentType      tipo MIME dichiarato
     * @param sizeBytes        dimensione dichiarata
     * @param entityType       tipo dell'entità a cui allegare il file, facoltativo
     * @param entityId         id dell'entità, da indicare insieme a entityType
     * @return il nuovo file in attesa di caricamento
     * @throws it.unical.geosentinel.shared.domain.exception.MediaRejectedException se tipo o dimensione non
     *         sono ammessi
     * @throws BusinessRuleViolationException se solo uno tra entityType ed entityId è indicato
     */
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

    /**
     * Segna il file come pronto dopo la verifica, salvando la dimensione reale e il checksum.
     *
     * @param actualSizeBytes dimensione reale dell'oggetto
     * @param checksum        ETag restituito dallo storage
     * @throws BusinessRuleViolationException se il file non è in attesa di caricamento
     */
    public void markReady(long actualSizeBytes, String checksum) {
        requirePending(MediaStatus.READY);
        this.status = MediaStatus.READY;
        this.sizeBytes = actualSizeBytes;
        this.checksum = checksum;
    }

    /**
     * Mette il file in quarantena perché il contenuto non è conforme.
     *
     * @throws BusinessRuleViolationException se il file non è in attesa di caricamento
     */
    public void quarantine() {
        requirePending(MediaStatus.QUARANTINED);
        this.status = MediaStatus.QUARANTINED;
    }

    /**
     * @param target stato di destinazione, usato nel messaggio di errore
     * @throws BusinessRuleViolationException se lo stato corrente non è {@code PENDING_UPLOAD}
     */
    private void requirePending(MediaStatus target) {
        if (this.status != MediaStatus.PENDING_UPLOAD) {
            throw new BusinessRuleViolationException("Cannot mark as %s a file with status %s".formatted(target, this.status));
        }
    }

    /**
     * @param userId utente da controllare
     * @return {@code true} se l'utente ha caricato il file
     */
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
    public String getCredit() { return credit; }
    public Long getVersion() { return version; }
}
