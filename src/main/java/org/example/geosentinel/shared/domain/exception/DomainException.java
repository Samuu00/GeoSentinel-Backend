package org.example.geosentinel.shared.domain.exception;

public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }
    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
