package it.unical.geosentinel.media.application;

import it.unical.geosentinel.media.application.port.ObjectStorage;
import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.infrastructure.persistence.MediaFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Cancella gli upload mai confermati: la riga PENDING_UPLOAD e l'oggetto eventualmente caricato (che altrimenti occuperebbe
// spazio per sempre, fuori da ogni quota). Con più istanze gira su ognuna: deleteIfPending è condizionale, il secondo salta
@Component
public class MediaCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);
    // Margine oltre la scadenza della policy: un upload finito all'ultimo secondo ha tempo per il confirm
    private static final Duration GRACE = Duration.ofHours(1);
    private static final int BATCH_SIZE = 100;

    private final MediaFileRepository mediaRepository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    public MediaCleanupJob(MediaFileRepository mediaRepository, ObjectStorage objectStorage, Clock clock) {
        this.mediaRepository = mediaRepository;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    // Prima la riga (il DELETE la blocca: un confirm concorrente attende e poi fallisce sulla @Version), poi l'oggetto.
    // Se lo storage fallisce la transazione torna indietro: le righe restano e si riprova al giro successivo
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @Transactional
    public void purgeExpiredUploads() {
        Instant cutoff = clock.instant().minus(MediaService.UPLOAD_EXPIRATION).minus(GRACE);
        List<MediaFile> expired = mediaRepository.findByStatusAndCreatedAtBefore(
                MediaStatus.PENDING_UPLOAD, cutoff, PageRequest.of(0, BATCH_SIZE));

        int purged = 0;
        for (MediaFile media : expired) {
            if (mediaRepository.deleteIfPending(media.getId(), MediaStatus.PENDING_UPLOAD) == 1) {
                objectStorage.deleteObject(media.getObjectKey());
                purged++;
            }
        }
        if (purged > 0) {
            log.info("Purged {} expired unconfirmed uploads", purged);
        }
    }
}
