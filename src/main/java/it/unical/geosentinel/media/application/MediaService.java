// Service dei file caricati dagli utenti (foto di sentieri, rifugi, segnalazioni e foto profilo). I file
// non passano dal backend: il service autorizza il caricamento diretto verso MinIO, poi alla conferma
// verifica il contenuto e lo rende disponibile. Qui si decidono anche chi può caricare, vedere e
// cancellare ogni file, la quota per utente e la sostituzione della foto profilo.
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
import it.unical.geosentinel.refuge.application.RefugeBookingService;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Logica applicativa dei media: caricamento in due passi (autorizzazione e conferma), download con URL
 * firmati, visibilità e cancellazione.
 */
@Service
public class MediaService {
    /** Validità dell'autorizzazione al caricamento. */
    static final Duration UPLOAD_EXPIRATION = Duration.ofMinutes(10);
    /** Validità degli URL firmati per il download. */
    private static final Duration DOWNLOAD_EXPIRATION = Duration.ofMinutes(5);
    /** Prefisso anno/mese (in UTC) delle object key, per distribuire gli oggetti in cartelle. */
    private static final DateTimeFormatter KEY_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    /** Namespace dell'advisory lock a due chiavi usato per la quota e per la foto profilo (vedi MediaFileRepository.lockOwner). */
    private static final int QUOTA_LOCK_NAMESPACE = 7001;

    private final MediaFileRepository mediaRepository;
    private final ObjectStorage objectStorage;
    private final TrailService trailService;
    private final HazardReportService hazardReportService;
    private final RefugeBookingService refugeService;
    private final Clock clock;

    /**
     * @param mediaRepository     repository dei metadati
     * @param objectStorage       storage degli oggetti
     * @param trailService        service dei sentieri, per verificare i target TRAIL
     * @param hazardReportService service delle segnalazioni, per verificare i target HAZARD_REPORT
     * @param refugeService       service dei rifugi, per verificare i target REFUGE
     * @param clock               orologio dell'applicazione
     */
    public MediaService(
            MediaFileRepository mediaRepository,
            ObjectStorage objectStorage,
            TrailService trailService,
            HazardReportService hazardReportService,
            RefugeBookingService refugeService,
            Clock clock
    ) {
        this.mediaRepository = mediaRepository;
        this.objectStorage = objectStorage;
        this.trailService = trailService;
        this.hazardReportService = hazardReportService;
        this.refugeService = refugeService;
        this.clock = clock;
    }

    /**
     * Genera la object key nel formato anno/mese/uuid.estensione. La chiave la sceglie il server: non usa
     * mai il nome del file inviato dal client (rischio di path traversal e di sovrascritture) né l'id del
     * proprietario, che finirebbe negli URL.
     *
     * @param now         istante corrente
     * @param contentType tipo MIME, per l'estensione
     * @return la chiave
     */
    private String generateObjectKey(Instant now, String contentType) {
        return "%s/%s%s".formatted(KEY_PREFIX.format(now), UUID.randomUUID(), MediaPolicy.extensionFor(contentType));
    }

    /**
     * Ripulisce il nome originale del file, che serve solo da etichetta: ogni carattere diverso da lettere,
     * cifre, punto, trattino basso e trattino diventa un trattino basso.
     *
     * @param filename nome inviato dal client
     * @return il nome ripulito, oppure "attachment" se assente
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Ricava dall'id dell'utente la chiave intera dell'advisory lock. È la stessa in caricamento e in
     * conferma, così le richieste dello stesso utente si mettono in fila. Due utenti possono avere la stessa
     * chiave: in quel caso si attendono a vicenda, senza errori.
     *
     * @param ownerId utente
     * @return la chiave del lock
     */
    private static int quotaLockKey(UUID ownerId) {
        return Long.hashCode(ownerId.getMostSignificantBits() ^ ownerId.getLeastSignificantBits());
    }

    /**
     * Avvia un caricamento: valida tipo e dimensione, controlla il target e la quota, salva i metadati e
     * restituisce l'autorizzazione al caricamento diretto. Le foto di sentieri e rifugi sono contenuto
     * ufficiale visibile a tutti, quindi servono il ruolo PARK_MANAGER e, per un rifugio, essere il suo
     * gestore (lo controlla checkTarget). Un escursionista può allegare foto alle proprie segnalazioni e
     * caricare la propria foto profilo.
     *
     * @param ownerId utente autenticato
     * @param cmd     dati del file
     * @return la sessione di caricamento
     * @throws it.unical.geosentinel.shared.domain.exception.MediaRejectedException se tipo, dimensione o quota
     *         non sono rispettati
     * @throws ResourceNotFoundException se il target non esiste o non è dell'utente
     * @throws AccessDeniedException     se l'utente non gestisce il rifugio indicato
     */
    @Transactional
    @PreAuthorize("#cmd.entityType() == null or #cmd.entityType() == T(it.unical.geosentinel.media.domain.MediaTargetType).HAZARD_REPORT or #cmd.entityType() == T(it.unical.geosentinel.media.domain.MediaTargetType).USER or hasRole('PARK_MANAGER')")
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

        // Lock per utente prima di leggere la quota: due richieste simultanee si mettono in fila e la
        // seconda vede la riga creata dalla prima.
        mediaRepository.lockOwner(QUOTA_LOCK_NAMESPACE, quotaLockKey(ownerId));
        long used = mediaRepository.sumQuotaBytes(ownerId, now.minus(UPLOAD_EXPIRATION), MediaStatus.READY, MediaStatus.PENDING_UPLOAD);
        MediaPolicy.checkQuota(used, cmd.sizeBytes());

        MediaFile saved = mediaRepository.save(mediaFile);
        // La policy limita la dimensione a quella dichiarata e il Content-Type a quello validato: è MinIO a farli rispettare.
        PresignedUpload upload = objectStorage.presignUpload(saved.getObjectKey(), saved.getContentType(), saved.getSizeBytes(), UPLOAD_EXPIRATION);
        return new UploadSessionView(saved.getId(), upload.url(), upload.formFields(), now.plus(UPLOAD_EXPIRATION));
    }

    /**
     * Controlla l'entità a cui allegare il file: un sentiero deve esistere; una segnalazione deve esistere
     * ed essere di chi carica; un rifugio deve essere gestito da chi carica; la foto profilo può essere
     * solo dell'utente stesso. Negli altri casi si risponde 404, così non si rivela se l'entità esiste
     * (tranne per i rifugi, che sono pubblici e ricevono un 403).
     *
     * @param ownerId    utente che carica
     * @param entityType tipo dell'entità, {@code null} se il file non è allegato
     * @param entityId   id dell'entità
     */
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
            // Limite noto: può caricare solo il gestore del rifugio, non un ADMIN, perché qui il ruolo non è
            // disponibile. Andrebbe aggiunto se servisse moderare le foto dei rifugi.
            case REFUGE -> {
                if (!refugeService.getRefugeOrThrow(entityId).isManagedBy(ownerId)) {
                    throw new AccessDeniedException("Only the refuge manager can add photos to this refuge");
                }
            }
            case USER -> {
                if (!entityId.equals(ownerId)) {
                    throw new ResourceNotFoundException("User", entityId);
                }
            }
        }
    }

    /**
     * Conferma un caricamento: sposta il file dallo staging all'oggetto finale, ne verifica dimensione e
     * formato e lo segna come pronto, oppure lo mette in quarantena e cancella l'oggetto. Si può ripetere:
     * se il file è già stato confermato restituisce l'esito precedente.
     *
     * @param ownerId utente autenticato
     * @param mediaId file da confermare
     * @return i metadati aggiornati
     * @throws ResourceNotFoundException      se il file non esiste o non è dell'utente
     * @throws BusinessRuleViolationException se lo storage non contiene ancora il file
     */
    @Transactional
    public MediaFileView confirmUpload(UUID ownerId, UUID mediaId) {
        MediaFile mediaFile = mediaRepository.findByIdAndOwnerId(mediaId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId));

        // READY o QUARANTINED: la conferma è già avvenuta, è un ritentativo del client e si restituisce l'esito.
        if (mediaFile.getStatus() != MediaStatus.PENDING_UPLOAD) {
            return MediaFileView.from(mediaFile);
        }

        // La verifica si fa sull'oggetto finale, dopo la promozione dallo staging: il client non può più sostituirlo.
        String key = mediaFile.getObjectKey();
        StoredObject stored = objectStorage.promoteUpload(key)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "File was not found on object storage. Upload must be completed prior to confirmation"
                ));

        // La dimensione è già limitata dalla policy di caricamento (qui il controllo è ripetuto per
        // sicurezza); il formato reale del contenuto si verifica solo in questo punto.
        boolean valid = stored.sizeBytes() <= mediaFile.getSizeBytes()
                && MediaPolicy.matchesSignature(mediaFile.getContentType(), objectStorage.readHead(key, MediaPolicy.SIGNATURE_BYTES));
        if (!valid) {
            mediaFile.quarantine();
            objectStorage.deleteObject(key);
            return MediaFileView.from(mediaFile);
        }

        mediaFile.markReady(stored.sizeBytes(), stored.etag());
        // Una foto profilo appena verificata sostituisce la precedente.
        if (mediaFile.getEntityType() == MediaTargetType.USER) {
            replaceAvatar(mediaFile);
        }
        return MediaFileView.from(mediaFile);
    }

    /**
     * Mantiene una sola foto profilo per utente, cancellando quelle precedenti. Il lock per utente (lo
     * stesso di initiateUpload) mette in fila due conferme concorrenti: la seconda vede la foto della
     * prima e la sostituisce, quindi vince l'ultima. Il lock si rilascia al commit.
     * Come in deleteAllOf si cancellano prima le righe (con un flush) e poi gli oggetti. Limite noto: se il
     * commit fallisce dopo la cancellazione degli oggetti, le righe delle foto precedenti tornano ma i loro
     * oggetti non esistono più.
     *
     * @param avatar foto profilo appena verificata
     */
    private void replaceAvatar(MediaFile avatar) {
        mediaRepository.lockOwner(QUOTA_LOCK_NAMESPACE, quotaLockKey(avatar.getOwnerId()));
        List<MediaFile> previous = mediaRepository
                .findByEntityTypeAndEntityIdAndStatus(MediaTargetType.USER, avatar.getEntityId(), MediaStatus.READY)
                .stream()
                .filter(m -> !m.getId().equals(avatar.getId()))
                .toList();
        mediaRepository.deleteAll(previous);
        mediaRepository.flush();
        previous.forEach(m -> objectStorage.deleteObject(m.getObjectKey()));
    }

    /**
     * Cancella tutti i media di un utente, durante la cancellazione dell'account, compresi quelli in
     * attesa (cancellare un oggetto mai caricato non dà errore). Prima si cancellano le righe con un flush,
     * così un errore del database emerge prima di toccare lo storage; poi gli oggetti.
     * Limite noto: se lo storage fallisce a metà, la transazione viene annullata ma gli oggetti già
     * cancellati no, e restano righe senza oggetto finché l'utente non ripete la cancellazione, che è
     * idempotente. Se diventasse un problema servirebbe un outbox.
     *
     * @param ownerId utente da cancellare
     */
    @Transactional
    public void deleteAllOf(UUID ownerId) {
        List<MediaFile> files = mediaRepository.findByOwnerId(ownerId, Pageable.unpaged()).getContent();
        mediaRepository.deleteAll(files);
        mediaRepository.flush();
        files.forEach(file -> objectStorage.deleteObject(file.getObjectKey()));
    }

    /**
     * Genera l'URL firmato per scaricare un file visibile all'utente.
     *
     * @param requesterId utente autenticato
     * @param mediaId     file richiesto
     * @return l'URL firmato, valido per {@link #DOWNLOAD_EXPIRATION}
     * @throws ResourceNotFoundException      se il file non esiste o non è visibile
     * @throws BusinessRuleViolationException se il file non è pronto
     */
    @Transactional(readOnly = true)
    public String getPresignedDownloadUrl(UUID requesterId, UUID mediaId) {
        MediaFile mediaFile = findVisible(requesterId, mediaId);

        if (mediaFile.getStatus() != MediaStatus.READY) {
            throw new BusinessRuleViolationException("MediaFile is not ready for download");
        }

        return objectStorage.generatePresignedDownloadUrl(mediaFile.getObjectKey(), mediaFile.getContentType(), DOWNLOAD_EXPIRATION);
    }

    /**
     * @param requesterId utente autenticato
     * @param mediaId     file richiesto
     * @return i metadati del file
     * @throws ResourceNotFoundException se il file non esiste o non è visibile
     */
    @Transactional(readOnly = true)
    public MediaFileView getMedia(UUID requesterId, UUID mediaId) {
        return MediaFileView.from(findVisible(requesterId, mediaId));
    }

    /**
     * Cerca un file applicando le regole di visibilità: il proprietario vede tutti i suoi file, compresa
     * la foto profilo; gli altri utenti solo quelli pronti e allegati a un'entità che possono vedere. Si
     * risponde 404 e non 403, per non rivelare che il file esiste.
     *
     * @param requesterId utente autenticato
     * @param mediaId     file richiesto
     * @return il file
     * @throws ResourceNotFoundException se il file non esiste o non è visibile
     */
    private MediaFile findVisible(UUID requesterId, UUID mediaId) {
        return mediaRepository.findById(mediaId)
                .filter(m -> m.isOwnedBy(requesterId) || (m.getStatus() == MediaStatus.READY && isTargetVisible(m.getEntityType(), m.getEntityId())))
                .orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId));
    }

    /**
     * Dice se l'entità a cui è allegato un file è visibile all'utente corrente. Lo switch è
     * un'espressione, quindi un nuovo valore dell'enum senza il suo ramo non compila.
     *
     * @param entityType tipo dell'entità, {@code null} se il file non è allegato
     * @param entityId   id dell'entità
     * @return {@code true} se l'entità è visibile
     */
    private boolean isTargetVisible(MediaTargetType entityType, UUID entityId) {
        if (entityType == null) {
            return false;
        }
        return switch (entityType) {
            case TRAIL -> true;
            case REFUGE -> refugeExists(entityId);
            case HAZARD_REPORT -> hazardVisible(entityId);
            // La foto profilo la vede solo il proprietario, e questo caso lo gestisce findVisible.
            case USER -> false;
        };
    }

    /**
     * I rifugi sono visibili a ogni utente autenticato, come per la rotta del dettaglio: basta che esistano.
     *
     * @param refugeId rifugio
     * @return {@code true} se il rifugio esiste
     */
    private boolean refugeExists(UUID refugeId) {
        try {
            refugeService.getRefugeOrThrow(refugeId);
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    /**
     * Una segnalazione segue la regola di HazardReportService.getHazard: una segnalazione respinta la
     * vedono solo il gestore e chi l'ha inviata. Chiamando il metodo attraverso il proxy di Spring si
     * riusa il suo {@code @PostAuthorize}, che resta l'unica fonte della regola.
     *
     * @param hazardId segnalazione
     * @return {@code true} se la segnalazione esiste ed è visibile all'utente corrente
     */
    private boolean hazardVisible(UUID hazardId) {
        try {
            hazardReportService.getHazard(hazardId);
            return true;
        } catch (AccessDeniedException | ResourceNotFoundException e) {
            return false;
        }
    }

    /**
     * Elenca i file pronti allegati a un'entità visibile.
     *
     * @param entityType tipo dell'entità
     * @param entityId   id dell'entità
     * @return i metadati dei file
     * @throws ResourceNotFoundException se l'entità non esiste o non è visibile
     */
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

    /**
     * @param ownerId  utente autenticato
     * @param pageable paginazione e ordinamento
     * @return la pagina dei file dell'utente, in ogni stato
     */
    @Transactional(readOnly = true)
    public Page<MediaFileView> getUserMedia(UUID ownerId, Pageable pageable) {
        return mediaRepository.findByOwnerId(ownerId, pageable)
                .map(MediaFileView::from);
    }

    /**
     * Restituisce l'id della foto profilo corrente, usato dalla rotta del profilo dell'utente. È quella in
     * stato READY, che di norma è unica grazie a replaceAvatar.
     *
     * @param userId utente
     * @return l'id della foto, oppure vuoto se l'utente non ne ha una
     */
    @Transactional(readOnly = true)
    public Optional<UUID> currentAvatarOf(UUID userId) {
        return mediaRepository.findByEntityTypeAndEntityIdAndStatus(MediaTargetType.USER, userId, MediaStatus.READY)
                .stream()
                .map(MediaFile::getId)
                .findFirst();
    }

    /**
     * Cancella un file. Può farlo il proprietario o un ADMIN, per moderazione; un altro utente riceve 404
     * se il file non gli è visibile, 403 altrimenti.
     *
     * @param requesterId      utente autenticato
     * @param requesterIsAdmin {@code true} se l'utente è un amministratore
     * @param mediaId          file da cancellare
     * @throws ResourceNotFoundException se il file non esiste o non è visibile
     * @throws AccessDeniedException     se l'utente vede il file ma non può cancellarlo
     */
    @Transactional
    public void deleteMedia(UUID requesterId, boolean requesterIsAdmin, UUID mediaId) {
        MediaFile mediaFile = requesterIsAdmin
                ? mediaRepository.findById(mediaId).orElseThrow(() -> new ResourceNotFoundException("MediaFile", mediaId))
                : findVisible(requesterId, mediaId);
        if (!requesterIsAdmin && !mediaFile.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("Only the owner or an admin can delete this media");
        }

        mediaRepository.delete(mediaFile);
        // L'oggetto si cancella dopo aver segnato la riga come da cancellare: se lo storage fallisce la
        // transazione viene annullata e la riga resta.
        objectStorage.deleteObject(mediaFile.getObjectKey());
    }
}
