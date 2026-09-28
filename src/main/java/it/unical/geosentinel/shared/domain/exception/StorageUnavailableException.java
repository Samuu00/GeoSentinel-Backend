package it.unical.geosentinel.shared.domain.exception;

// 503: l'object storage non risponde. Qui perché l'handler è in shared
public class StorageUnavailableException extends DomainException {
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
