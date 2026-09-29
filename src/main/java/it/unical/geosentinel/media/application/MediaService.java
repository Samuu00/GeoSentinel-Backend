package it.unical.geosentinel.media.application;

import it.unical.geosentinel.media.application.dto.MediaCommands.InitUploadCommand;
import it.unical.geosentinel.media.application.dto.MediaCommands.MediaFileView;
import it.unical.geosentinel.media.application.dto.MediaCommands.UploadSessionView;
import it.unical.geosentinel.media.application.port.ObjectStorage;
import it.unical.geosentinel.media.application.port.ObjectStorage.PresignedUpload;
import it.unical.geosentinel.media.application.port.ObjectStorage.StoredObject;
import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaPolicy;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import it.unical.geosentinel.media.infrastructure.persistence.MediaFileRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.trail.application.HazardReportService;
import it.unical.geosentinel.trail.application.TrailService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class MediaService {
    static final Duration UPLOAD_EXPIRATION = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_EXPIRATION = Duration.ofMinutes(5);
    private static final DateTimeFormatter KEY_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    // Namespace dell'advisory lock a due chiavi usato per la quota (vedi MediaFileRepository.lockOwner)
    private static final int QUOTA_LOCK_NAMESPACE = 7001;

    private final MediaFileRepository mediaRepository;
    private final ObjectStorage objectStorage;
    private final TrailService trailService;
    private final HazardReportService hazardReportService;
    private final Clock clock;

    public MediaService(
            MediaFileRepository mediaRepository,
            ObjectStorage objectStorage,
            TrailService trailService,
            HazardReportService hazardReportService,
            Clock clock
    ) {
        this.mediaRepository = mediaRepository;
        this.objectStorage = objectStorage;
        this.trailService = trailService;
        this.hazardReportService = hazardReportService;
        this.clock = clock;
    }

    // Chiave generata dal server: mai il filename del client (path traversal, sovrascritture) né l'ownerId (finirebbe negli URL)
    private String generateObjectKey(Instant now, String contentType) {
        return "%s/%s%s".formatted(KEY_PREFIX.format(now), UUID.randomUUID(), MediaPolicy.extensionFor(contentType));
    }

    // Il nome originale serve solo da etichetta
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Le foto di un sentiero sono contenuto ufficiale (visibile a tutti): solo PARK_MANAGER.
    // Un escursionista allega foto alle proprie segnalazioni di pericolo
    @Transactional
    @PreAuthorize("#cmd.entityType() != T(it.unical.geosentinel.media.domain.MediaTargetType).TRAIL or hasRole('PARK_MANAGER')")
    public UploadSessionView initiateUpload(UUID ownerId, InitUploadCommand cmd) {
        Instant now = clock.instant();
        MediaFile mediaFile = MediaFile.initUpload(
                ownerId,
                generateObjectKey(now, cmd.contentType()),
                sanitizeFilename(cmd.originalFilename()),
                cmd.contentType(),
                cmd.sizeBytes(),
                cmd.entityType(),
                cmd.entityId()
        );
        checkTarget(ownerId, cmd.entityType(), cmd.entityId());

        // Lock per utente prima di leggere la quota: due richieste simultanee si mettono in fila e la seconda vede la riga della prima
        mediaRepository.lockOwner(QUOTA_LOCK_NAMESPACE, Long.hashCode(ownerId.getMostSignificantBits() ^ ownerId.getLeastSignificantBits()));
        long used = mediaRepository.sumQuotaBytes(ownerId, now.minus(UPLOAD_EXPIRATION), MediaStatus.READY, MediaStatus.PENDING_UPLOAD);
        MediaPolicy.checkQuota(used, cmd.sizeBytes());

        MediaFile saved = mediaRepository.save(mediaFile);
        // La policy limita la dimensione a quella dichiarata e il Content-Type a quello validato: li fa rispettare MinIO
        PresignedUpload upload = objectStorage.presignUpload(saved.getObjectKey(), saved.getContentType(), saved.getSizeBytes(), UPLOAD_EXPIRATION);
        return new UploadSessionView(saved.getId(), upload.url(), upload.formFields(), now.plus(UPLOAD_EXPIRATION));
    }

    // Un sentiero deve esistere; una segnalazione deve esistere ed essere di chi carica (404 altrimenti: non se ne rivela l'esistenza)
    private void checkTarget(UUID ownerId, MediaTargetType entityType, UUID entityId) {
        if (entityType == null) {
            return;
        }
        switch (entityType) {
            case TRAIL -> trailService.getTrailOrThrow(entityId);
            case HAZARD_REPORT -> {
                if (!hazardReportService.getHazardOrThrow(entityId).getReporterId().equals(ownerId)) {
                    throw new ResourceNotFoundException("Hazard", entityId);
                }
            }
        }
    }

    @Transactional
    public MediaFileView confirmUpload(UUID ownerId, UUID mediaId) {
        MediaFile mediaFile = mediaRepository.findByIdAndOwnerId(mediaId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId));

        // READY o QUARANTINED: confirm già fatto, si restituisce l'esito (retry del client)
        if (mediaFile.getStatus() != MediaStatus.PENDING_UPLOAD) {
            return MediaFileView.from(mediaFile);
        }

        String key = mediaFile.getObjectKey();
        StoredObject stored = objectStorage.stat(key)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "File was not found on object storage. Upload must be completed prior to confirmation"
                ));

        // La dimensione la limita già la policy di upload (controllo ripetuto per sicurezza); il contenuto si verifica solo qui
        boolean valid = stored.sizeBytes() <= mediaFile.getSizeBytes()
                && MediaPolicy.matchesSignature(mediaFile.getContentType(), objectStorage.readHead(key, MediaPolicy.SIGNATURE_BYTES));
        if (!valid) {
            mediaFile.quarantine();
            objectStorage.deleteObject(key);
            return MediaFileView.from(mediaFile);
        }

        mediaFile.markReady(stored.sizeBytes(), stored.etag());
        return MediaFileView.from(mediaFile);
    }

    // Cancellazione dell'account (M7.2): tutti i media, anche PENDING (la delete di un oggetto mai caricato non fallisce).
    // Come deleteMedia: prima le righe (flush, così un errore del DB arriva prima di toccare lo storage), poi gli oggetti.
    // ponytail: se lo storage fallisce a metà la transazione torna indietro ma gli oggetti già cancellati no; restano
    // righe senza oggetto finché l'utente non riprova la cancellazione (idempotente). Serve un outbox se diventa un problema
    @Transactional
    public void deleteAllOf(UUID ownerId) {
        List<MediaFile> files = mediaRepository.findByOwnerId(ownerId, Pageable.unpaged()).getContent();
        mediaRepository.deleteAll(files);
        mediaRepository.flush();
        files.forEach(file -> objectStorage.deleteObject(file.getObjectKey()));
    }

    @Transactional(readOnly = true)
    public String getPresignedDownloadUrl(UUID requesterId, UUID mediaId) {
        MediaFile mediaFile = findVisible(requesterId, mediaId);

        if (mediaFile.getStatus() != MediaStatus.READY) {
            throw new BusinessRuleViolationException("MediaFile is not ready for download");
        }

        return objectStorage.generatePresignedDownloadUrl(mediaFile.getObjectKey(), mediaFile.getContentType(), DOWNLOAD_EXPIRATION);
    }

    @Transactional(readOnly = true)
    public MediaFileView getMedia(UUID requesterId, UUID mediaId) {
        return MediaFileView.from(findVisible(requesterId, mediaId));
    }

    // Il proprietario vede tutti i suoi file; gli altri solo quelli pronti, allegati a un'entità che possono vedere.
    // 404, non 403: non se ne rivela l'esistenza
    private MediaFile findVisible(UUID requesterId, UUID mediaId) {
        return mediaRepository.findById(mediaId)
                .filter(m -> m.isOwnedBy(requesterId) || (m.getStatus() == MediaStatus.READY && isTargetVisible(m.getEntityType(), m.getEntityId())))
                .orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId));
    }

    // I sentieri sono pubblici; una segnalazione segue la regola di HazardReportService.getHazard
    // (una REJECTED la vedono solo PARK_MANAGER e chi l'ha fatta): unica fonte della regola, riusata tramite il proxy
    private boolean isTargetVisible(MediaTargetType entityType, UUID entityId) {
        if (entityType == null) {
            return false;
        }
        if (entityType == MediaTargetType.TRAIL) {
            return true;
        }
        try {
            hazardReportService.getHazard(entityId);
            return true;
        } catch (AccessDeniedException | ResourceNotFoundException e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<MediaFileView> getAttachedMedia(MediaTargetType entityType, UUID entityId) {
        if (!isTargetVisible(entityType, entityId)) {
            throw new ResourceNotFoundException(entityType.name(), entityId);
        }
        return mediaRepository.findByEntityTypeAndEntityIdAndStatus(entityType, entityId, MediaStatus.READY)
                .stream()
                .map(MediaFileView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<MediaFileView> getUserMedia(UUID ownerId, Pageable pageable) {
        return mediaRepository.findByOwnerId(ownerId, pageable)
                .map(MediaFileView::from);
    }

    // Il proprietario o un ADMIN (moderazione); un altro utente riceve 404 se il file non gli è visibile, 403 altrimenti
    @Transactional
    public void deleteMedia(UUID requesterId, boolean requesterIsAdmin, UUID mediaId) {
        MediaFile mediaFile = requesterIsAdmin
                ? mediaRepository.findById(mediaId).orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId))
                : findVisible(requesterId, mediaId);
        if (!requesterIsAdmin && !mediaFile.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("Only the owner or an admin can delete this media");
        }

        mediaRepository.delete(mediaFile);
        // Dopo il delete della riga: se lo storage fallisce, la transazione torna indietro e la riga resta
        objectStorage.deleteObject(mediaFile.getObjectKey());
    }
}
