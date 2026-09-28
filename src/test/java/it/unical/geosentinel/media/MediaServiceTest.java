package it.unical.geosentinel.media;

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

class MediaServiceTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private final MediaFileRepository repository = mock(MediaFileRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final HazardReportService hazards = mock(HazardReportService.class);
    private final MediaService service = new MediaService(repository, storage, mock(TrailService.class), hazards,
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC));

    private MediaFile readyHazardPhoto(UUID hazardId) {
        MediaFile file = MediaFile.initUpload(OWNER, "2026/09/x.jpg", "x.jpg", "image/jpeg", 10, MediaTargetType.HAZARD_REPORT, hazardId);
        file.markReady(10, "etag");
        return file;
    }

    @Test
    void photoOfRejectedHazardIsHiddenFromOtherUsers() {
        UUID hazardId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(repository.findById(mediaId)).thenReturn(Optional.of(readyHazardPhoto(hazardId)));
        // @PostAuthorize di getHazard nega la segnalazione REJECTED a chi non è PARK_MANAGER né il segnalatore
        when(hazards.getHazard(hazardId)).thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.getMedia(STRANGER, mediaId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getAttachedMedia(MediaTargetType.HAZARD_REPORT, hazardId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ownerAlwaysSeesOwnMedia() {
        UUID hazardId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(repository.findById(mediaId)).thenReturn(Optional.of(readyHazardPhoto(hazardId)));
        when(hazards.getHazard(hazardId)).thenThrow(new AccessDeniedException("denied"));

        assertThat(service.getMedia(OWNER, mediaId).status()).isEqualTo(MediaStatus.READY);
    }

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
