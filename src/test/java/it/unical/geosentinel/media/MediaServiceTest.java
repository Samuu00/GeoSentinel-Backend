// Test unitario di MediaService, il servizio che gestisce foto e allegati caricati dagli utenti, con
// repository, storage e servizi collegati sostituiti da mock di Mockito e orologio fisso, senza Spring.
// Riguarda la visibilità delle foto allegate alle segnalazioni e l'ordine delle operazioni che proteggono
// la quota di spazio di ogni utente.
package it.unical.geosentinel.media;

import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.media.application.MediaService;
import it.unical.geosentinel.media.application.dto.MediaCommands.InitUploadCommand;
import it.unical.geosentinel.media.application.port.ObjectStorage;
import it.unical.geosentinel.media.application.port.ObjectStorage.PresignedUpload;
import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import it.unical.geosentinel.media.infrastructure.persistence.MediaFileRepository;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.trail.application.HazardReportService;
import it.unical.geosentinel.trail.application.TrailService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifica i controlli di accesso sulle foto delle segnalazioni e la sequenza lock, lettura della quota e
 * salvataggio all'avvio di un caricamento.
 */
class MediaServiceTest {

    /** Utente che ha caricato la foto. */
    private static final UUID OWNER = UUID.randomUUID();

    /** Un altro utente qualsiasi, senza permessi particolari. */
    private static final UUID STRANGER = UUID.randomUUID();

    /** Repository dei file finto. */
    private final MediaFileRepository repository = mock(MediaFileRepository.class);

    /** Storage degli oggetti finto (nell'applicazione è MinIO). */
    private final ObjectStorage storage = mock(ObjectStorage.class);

    /** Servizio delle segnalazioni, usato da MediaService per decidere se la foto è visibile. */
    private final HazardReportService hazards = mock(HazardReportService.class);

    /** Servizio sotto test. */
    private final MediaService service = new MediaService(repository, storage, mock(TrailService.class), hazards, mock(RefugeBookingService.class),
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC));

    /**
     * Crea una foto già confermata, caricata da {@link #OWNER} e allegata a una segnalazione.
     *
     * @param hazardId l'id della segnalazione a cui è allegata
     * @return il file nello stato {@code READY}
     */
    private MediaFile readyHazardPhoto(UUID hazardId) {
        MediaFile file = MediaFile.initUpload(OWNER, "2026/09/x.jpg", "x.jpg", "image/jpeg", 10, MediaTargetType.HAZARD_REPORT, hazardId);
        file.markReady(10, "etag");
        return file;
    }

    /**
     * Se l'utente non può vedere la segnalazione (ad esempio perché è stata rifiutata), non deve vedere neanche
     * le sue foto: sia la lettura del singolo file sia la lista degli allegati devono rispondere come se la
     * risorsa non esistesse, con {@link ResourceNotFoundException}.
     */
    @Test
    void photoOfRejectedHazardIsHiddenFromOtherUsers() {
        UUID hazardId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(repository.findById(mediaId)).thenReturn(Optional.of(readyHazardPhoto(hazardId)));
        // Simula il controllo @PostAuthorize di getHazard, che nega una segnalazione REJECTED a chi non è
        // gestore del parco né autore della segnalazione.
        when(hazards.getHazard(hazardId)).thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.getMedia(STRANGER, mediaId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getAttachedMedia(MediaTargetType.HAZARD_REPORT, hazardId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Chi ha caricato la foto deve poterla sempre leggere, anche se la segnalazione non gli è più visibile. */
    @Test
    void ownerAlwaysSeesOwnMedia() {
        UUID hazardId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(repository.findById(mediaId)).thenReturn(Optional.of(readyHazardPhoto(hazardId)));
        when(hazards.getHazard(hazardId)).thenThrow(new AccessDeniedException("denied"));

        assertThat(service.getMedia(OWNER, mediaId).status()).isEqualTo(MediaStatus.READY);
    }

    /**
     * All'avvio di un caricamento il servizio deve prima prendere il lock sull'utente (un advisory lock di
     * PostgreSQL), poi leggere la quota usata e infine salvare il nuovo file. Se la quota fosse letta prima del
     * lock, due caricamenti paralleli dello stesso utente potrebbero leggere lo stesso valore e superarla.
     */
    @Test
    void quotaIsReadOnlyAfterTheOwnerLock() {
        when(repository.save(any(MediaFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any()))
                .thenReturn(new PresignedUpload("http://localhost:9000/geosentinel-media", Map.of()));

        service.initiateUpload(OWNER, new InitUploadCommand("x.jpg", "image/jpeg", 10, null, null));

        InOrder order = inOrder(repository);
        order.verify(repository).lockOwner(anyInt(), anyInt());
        order.verify(repository).sumQuotaBytes(eq(OWNER), any(), any(), any());
        order.verify(repository).save(any(MediaFile.class));
    }
}
