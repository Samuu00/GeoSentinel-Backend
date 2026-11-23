// Eccezione della feature media: il file che l'utente vuole caricare non rispetta la policy (tipo non
// ammesso, dimensione eccessiva o quota esaurita). Il motivo decide lo status HTTP in
// GlobalExceptionHandler: 415, 413 o 422. Il nome evita lo scontro, negli auto-import dell'IDE, con
// PayloadTooLargeException di Spring.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che un file è stato rifiutato dalla policy dei media, con il motivo del rifiuto.
 */
public class MediaRejectedException extends DomainException {
    /**
     * Motivo del rifiuto, usato per scegliere status e slug della risposta.
     */
    public enum Reason {
        /** Content type non ammesso: 415 {@code media-unsupported-type}. */
        UNSUPPORTED_TYPE,
        /** File più grande del limite: 413 {@code media-payload-too-large}. */
        TOO_LARGE,
        /** Superata la quota di spazio dell'utente: 422 {@code media-quota-exceeded}. */
        QUOTA_EXCEEDED }

    /** Motivo del rifiuto. */
    private final Reason reason;

    /**
     * @param reason  motivo del rifiuto
     * @param message descrizione dell'errore per il client
     */
    public MediaRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * @return il motivo del rifiuto
     */
    public Reason getReason() {
        return reason;
    }
}
