// Test unitario delle regole sui file caricati dagli utenti (foto di segnalazioni, sentieri e rifugi):
// controllo della firma binaria rispetto al tipo dichiarato, quota per utente, tipi e dimensioni ammessi,
// e transizioni di stato dell'entità MediaFile. Non usa Spring né MinIO.
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

/**
 * Verifica {@link MediaPolicy} e le regole di dominio di {@link MediaFile}.
 */
class MediaPolicyTest {

    /** Primi byte di un file JPEG (marcatore SOI seguito dall'intestazione JFIF). */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    /** Primi byte di un file PNG (firma di 8 byte e inizio del primo blocco). */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    /** Primi byte di un file WebP (contenitore RIFF con il tipo WEBP). */
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P'};

    /**
     * Ogni firma deve essere riconosciuta solo per il proprio tipo MIME: un PNG dichiarato come JPEG o un WebP
     * dichiarato come PNG non devono passare.
     */
    @Test
    void signatureMatchesOnlyTheDeclaredType() {
        assertThat(MediaPolicy.matchesSignature("image/jpeg", JPEG)).isTrue();
        assertThat(MediaPolicy.matchesSignature("image/png", PNG)).isTrue();
        assertThat(MediaPolicy.matchesSignature("image/webp", WEBP)).isTrue();

        assertThat(MediaPolicy.matchesSignature("image/jpeg", PNG)).isFalse();
        assertThat(MediaPolicy.matchesSignature("image/png", WEBP)).isFalse();
    }

    /** Un file HTML dichiarato come JPEG non deve superare il controllo della firma. */
    @Test
    void htmlDeclaredAsJpegIsRejected() {
        byte[] html = "<html><script>".getBytes(StandardCharsets.US_ASCII);
        assertThat(MediaPolicy.matchesSignature("image/jpeg", html)).isFalse();
    }

    /** Un file troncato, più corto della firma attesa, o vuoto deve essere rifiutato. */
    @Test
    void truncatedFileIsRejected() {
        assertThat(MediaPolicy.matchesSignature("image/webp", new byte[]{'R', 'I', 'F', 'F'})).isFalse();
        assertThat(MediaPolicy.matchesSignature("image/jpeg", new byte[0])).isFalse();
    }

    /**
     * La quota conta anche i byte richiesti dal nuovo caricamento: arrivare esattamente al limite è ammesso,
     * superarlo di un byte produce una {@link MediaRejectedException} con motivo {@code QUOTA_EXCEEDED}.
     */
    @Test
    void quotaCountsRequestedBytes() {
        MediaPolicy.checkQuota(MediaPolicy.USER_QUOTA_BYTES - 10, 10);
        assertThatThrownBy(() -> MediaPolicy.checkQuota(MediaPolicy.USER_QUOTA_BYTES - 10, 11))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.QUOTA_EXCEEDED));
    }

    /**
     * Un tipo non supportato e un file più grande del massimo devono essere rifiutati, ciascuno con il proprio
     * motivo ({@code UNSUPPORTED_TYPE} e {@code TOO_LARGE}), così il client può spiegare l'errore.
     */
    @Test
    void typeAndSizeViolationsCarryTheirReason() {
        assertThatThrownBy(() -> MediaPolicy.validateImageUpload("text/html", 10))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_TYPE));
        assertThatThrownBy(() -> MediaPolicy.validateImageUpload("image/png", MediaPolicy.MAX_FILE_SIZE_BYTES + 1))
                .isInstanceOfSatisfying(MediaRejectedException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    /**
     * Tipo e id della risorsa a cui si allega il file vanno indicati insieme o per niente: un tipo senza id deve
     * produrre una {@link BusinessRuleViolationException}.
     */
    @Test
    void targetTypeAndIdMustComeTogether() {
        assertThatThrownBy(() -> MediaFile.initUpload(UUID.randomUUID(), "2026/09/x.jpg", "x.jpg", "image/jpeg", 10,
                MediaTargetType.TRAIL, null))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * Un file confermato passa a {@code READY} con la dimensione reale misurata sullo storage; da quel momento
     * non può più essere messo in quarantena.
     */
    @Test
    void confirmedFileCannotBeQuarantined() {
        MediaFile file = MediaFile.initUpload(UUID.randomUUID(), "2026/09/x.jpg", "x.jpg", "image/jpeg", 10, null, null);
        file.markReady(8, "etag");

        assertThat(file.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(file.getSizeBytes()).isEqualTo(8);
        assertThatThrownBy(file::quarantine).isInstanceOf(BusinessRuleViolationException.class);
    }
}
