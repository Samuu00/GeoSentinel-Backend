package it.unical.geosentinel.shared.domain.exception;

// 503: il provider di login non risponde (chiavi pubbliche di Google). Qui perché l'handler è in shared
public class IdentityProviderUnavailableException extends DomainException {
    public IdentityProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
