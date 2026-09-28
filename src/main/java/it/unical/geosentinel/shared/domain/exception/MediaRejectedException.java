package it.unical.geosentinel.shared.domain.exception;

// File rifiutato dalla policy dei media. Il motivo decide lo status (in GlobalExceptionHandler): 415, 413 o 422.
// Nome scelto per non scontrarsi con org.springframework.web.server.PayloadTooLargeException negli auto-import
public class MediaRejectedException extends DomainException {
    public enum Reason { UNSUPPORTED_TYPE, TOO_LARGE, QUOTA_EXCEEDED }

    private final Reason reason;

    public MediaRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
