package it.unical.geosentinel.shared.domain.exception;

public class MfaAlreadyEnabledException extends DomainException {
    public MfaAlreadyEnabledException() {
        super("Two-factor authentication is already enabled");
    }
}