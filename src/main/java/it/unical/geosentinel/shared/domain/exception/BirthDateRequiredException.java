package it.unical.geosentinel.shared.domain.exception;

// Primo accesso con Google senza data di nascita -> 422 birth-date-required: l'app la chiede e ripete la richiesta
public class BirthDateRequiredException extends DomainException {
    public BirthDateRequiredException(String message) {
        super(message);
    }
}
