package it.unical.geosentinel.media.domain;

import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException;
import it.unical.geosentinel.shared.domain.exception.MediaRejectedException.Reason;

import java.util.Map;

public final class MediaPolicy {
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    public static final long USER_QUOTA_BYTES = 1024L * 1024 * 1024;  // 1 GB
    // Byte da leggere dall'oggetto per riconoscerne il formato (WebP ha la firma all'offset 8)
    public static final int SIGNATURE_BYTES = 12;

    // MIME ammesso -> estensione della object key
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp"
    );

    private MediaPolicy() {}

    // Unico punto in cui stanno le regole di tipo e dimensione: il DTO controlla solo che i campi ci siano
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

    public static void checkQuota(long usedBytes, long requestedBytes) {
        if (usedBytes + requestedBytes > USER_QUOTA_BYTES) {
            throw new MediaRejectedException(Reason.QUOTA_EXCEEDED,
                    "Storage quota exceeded: %d of %d bytes already in use".formatted(usedBytes, USER_QUOTA_BYTES));
        }
    }

    public static String extensionFor(String contentType) {
        return EXTENSIONS.get(contentType);
    }

    // Il Content-Type dichiarato dal client non prova nulla: si controllano i primi byte del file
    public static boolean matchesSignature(String contentType, byte[] head) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(head, 0, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
    }

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
