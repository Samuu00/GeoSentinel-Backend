package org.example.geosentinel.shared.domain.exception;

public class BusinessRuleViolationException extends DomainException {
    public BusinessRuleViolationException(String message) {
        super(message);
    }
    public BusinessRuleViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
