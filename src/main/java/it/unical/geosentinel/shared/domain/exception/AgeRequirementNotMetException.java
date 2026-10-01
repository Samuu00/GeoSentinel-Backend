package it.unical.geosentinel.shared.domain.exception;

// Età sotto la soglia del GDPR art. 8 -> 422 age-requirement-not-met
public class AgeRequirementNotMetException extends DomainException {
    public AgeRequirementNotMetException(String message) {
        super(message);
    }
}
