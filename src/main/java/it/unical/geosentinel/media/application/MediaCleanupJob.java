// Job pianificato che elimina i caricamenti mai confermati: la riga in PENDING_UPLOAD e l'eventuale
// oggetto già caricato nello storage, che altrimenti occuperebbe spazio per sempre senza rientrare in
// nessuna quota.
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

/**
 * Pulizia periodica dei caricamenti scaduti. Con più istanze del backend il job gira su ognuna, ma la
 * cancellazione è condizionata allo stato: se due istanze trattano la stessa riga, la seconda la salta.
 */
@Component
public class MediaCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);
    /** Margine oltre la scadenza dell'autorizzazione al caricamento, per lasciare il tempo alla conferma di un upload finito all'ultimo secondo. */
    private static final Duration GRACE = Duration.ofHours(1);
    /** Numero massimo di righe trattate a ogni esecuzione. */
    private static final int BATCH_SIZE = 100;

    private final MediaFileRepository mediaRepository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    /**
     * @param mediaRepository repository dei media
     * @param objectStorage   storage degli oggetti
     * @param clock           orologio dell'applicazione
     */
    public MediaCleanupJob(MediaFileRepository mediaRepository, ObjectStorage objectStorage, Clock clock) {
        this.mediaRepository = mediaRepository;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    /**
     * Ogni 15 minuti (il primo giro 5 minuti dopo l'avvio) elimina fino a {@link #BATCH_SIZE} caricamenti
     * scaduti. Per ciascuno cancella prima la riga, poi l'oggetto: il DELETE blocca la riga, quindi una
     * conferma concorrente attende e poi fallisce sul controllo di {@code @Version}. Se lo storage
     * fallisce la transazione viene annullata, le righe restano e si riprova al giro successivo.
     */
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
