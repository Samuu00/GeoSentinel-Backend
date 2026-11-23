// Regole di dominio sui file caricati: tipi ammessi, dimensione massima, quota di spazio per utente e
// riconoscimento del formato reale dai primi byte del file. È l'unico punto in cui stanno queste regole;
// i DTO controllano solo che i campi siano presenti.
package it.unical.geosentinel.media.domain;

import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException.Reason;

import java.util.Map;

/**
 * Classe di utilità con le regole sui media. Non istanziabile.
 */
public final class MediaPolicy {
    /** Dimensione massima di un singolo file: 10 MB. */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    /** Spazio totale disponibile per utente: 1 GB. */
    public static final long USER_QUOTA_BYTES = 1024L * 1024 * 1024;
    /** Byte da leggere dall'inizio del file per riconoscerne il formato: la firma di WebP inizia all'offset 8. */
    public static final int SIGNATURE_BYTES = 12;

    /** Tipi MIME ammessi, ciascuno con l'estensione usata nella object key. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp"
    );

    /** Costruttore privato: la classe ha solo metodi statici. */
    private MediaPolicy() {}

    /**
     * Controlla tipo e dimensione dichiarati per un nuovo caricamento.
     *
     * @param contentType tipo MIME dichiarato dal client
     * @param sizeBytes   dimensione dichiarata, in byte
     * @throws MediaRejectedException         se il tipo non è ammesso o il file supera il limite
     * @throws BusinessRuleViolationException se la dimensione non è positiva
     */
    public static void validateImageUpload(String contentType, long sizeBytes) {
        if (contentType == null || !EXTENSIONS.containsKey(contentType)) {
            throw new MediaRejectedException(Reason.UNSUPPORTED_TYPE,
                    "Unsupported media type '%s'. Allowed types: %s".formatted(contentType, EXTENSIONS.keySet()));
        }
        if (sizeBytes <= 0) {
            throw new BusinessRuleViolationException("File size must be greater than 0 bytes");
        }
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            throw new MediaRejectedException(Reason.TOO_LARGE,
                    "File size %d bytes exceeds the maximum of %d bytes".formatted(sizeBytes, MAX_FILE_SIZE_BYTES));
        }
    }

    /**
     * Verifica che un nuovo file stia nella quota dell'utente.
     *
     * @param usedBytes      spazio già occupato, in byte
     * @param requestedBytes dimensione del nuovo file, in byte
     * @throws MediaRejectedException se la somma supera {@link #USER_QUOTA_BYTES}
     */
    public static void checkQuota(long usedBytes, long requestedBytes) {
        if (usedBytes + requestedBytes > USER_QUOTA_BYTES) {
            throw new MediaRejectedException(Reason.QUOTA_EXCEEDED,
                    "Storage quota exceeded: %d of %d bytes already in use".formatted(usedBytes, USER_QUOTA_BYTES));
        }
    }

    /**
     * @param contentType tipo MIME ammesso
     * @return l'estensione da usare nella object key, oppure {@code null} se il tipo non è ammesso
     */
    public static String extensionFor(String contentType) {
        return EXTENSIONS.get(contentType);
    }

    /**
     * Controlla che i primi byte del file corrispondano alla firma (magic bytes) del formato dichiarato.
     * Il Content-Type inviato dal client non prova nulla sul contenuto reale, quindi lo si verifica sul file.
     *
     * @param contentType tipo MIME dichiarato
     * @param head        primi byte del file, almeno {@link #SIGNATURE_BYTES}
     * @return {@code true} se il contenuto è del formato dichiarato
     */
    public static boolean matchesSignature(String contentType, byte[] head) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
    }

    /**
     * Confronta una sequenza di byte attesa con i dati a partire da una posizione.
     *
     * @param data     byte letti dal file
     * @param offset   posizione da cui confrontare
     * @param expected valori attesi, come interi senza segno da 0 a 255
     * @return {@code true} se tutti i byte coincidono; {@code false} anche se i dati sono troppo corti
     */
    private static boolean startsWith(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
