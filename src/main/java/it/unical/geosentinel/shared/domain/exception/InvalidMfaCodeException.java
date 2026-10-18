package it.unical.geosentinel.shared.domain.exception;

// Codice TOTP sbagliato, già usato o recovery non valido: un solo errore, come InvalidResetCodeException
public class InvalidMfaCodeException extends DomainException {
    public InvalidMfaCodeException() {
        super("Invalid two-factor code");
    }
}