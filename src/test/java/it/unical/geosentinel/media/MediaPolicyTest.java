package it.unical.geosentinel.media;

import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaPolicy;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException.Reason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaPolicyTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void signatureMatchesOnlyTheDeclaredType() {
        assertThat(MediaPolicy.matchesSignature("image/jpeg", JPEG)).isTrue();
        assertThat(MediaPolicy.matchesSignature("image/png", PNG)).isTrue();
        assertThat(MediaPolicy.matchesSignature("image/webp", WEBP)).isTrue();

        assertThat(MediaPolicy.matchesSignature("image/jpeg", PNG)).isFalse();
        assertThat(MediaPolicy.matchesSignature("image/png", WEBP)).isFalse();
    }

    @Test
    void htmlDeclaredAsJpegIsRejected() {
        byte[] html = "<html><script>".getBytes(StandardCharsets.US_ASCII);
        assertThat(MediaPolicy.matchesSignature("image/jpeg", html)).isFalse();
    }

    @Test
    void truncatedFileIsRejected() {
        assertThat(MediaPolicy.matchesSignature("image/webp", new byte[]{'R', 'I', 'F', 'F'})).isFalse();
        assertThat(MediaPolicy.matchesSignature("image/jpeg", new byte[0])).isFalse();
    }

    @Test
    void quotaCountsRequestedBytes() {
        MediaPolicy.checkQuota(MediaPolicy.USER_QUOTA_BYTES - 10, 10);
        assertThatThrownBy(() -> MediaPolicy.checkQuota(MediaPolicy.USER_QUOTA_BYTES - 10, 11))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.QUOTA_EXCEEDED));
    }

    @Test
    void typeAndSizeViolationsCarryTheirReason() {
        assertThatThrownBy(() -> MediaPolicy.validateImageUpload("text/html", 10))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_TYPE));
        assertThatThrownBy(() -> MediaPolicy.validateImageUpload("image/png", MediaPolicy.MAX_FILE_SIZE_BYTES + 1))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void targetTypeAndIdMustComeTogether() {
        assertThatThrownBy(() -> MediaFile.initUpload(UUID.randomUUID(), "2026/09/x.jpg", "x.jpg", "image/jpeg", 10,
                MediaTargetType.TRAIL, null))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void confirmedFileCannotBeQuarantined() {
        MediaFile file = MediaFile.initUpload(UUID.randomUUID(), "2026/09/x.jpg", "x.jpg", "image/jpeg", 10, null, null);
        file.markReady(8, "etag");

        assertThat(file.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(file.getSizeBytes()).isEqualTo(8);
        assertThatThrownBy(file::quarantine).isInstanceOf(BusinessRuleViolationException.class);
    }
}
