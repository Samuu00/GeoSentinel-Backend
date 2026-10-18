package it.unical.geosentinel.shared.domain.exception;

// Password giusta, 2FA attiva, codice assente: il client chiede il codice e rimanda la stessa richiesta
public class MfaRequiredException extends DomainException {
    public MfaRequiredException() {
        super("Two-factor code required");
    }
}