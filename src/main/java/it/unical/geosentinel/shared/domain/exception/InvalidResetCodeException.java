package it.unical.geosentinel.shared.domain.exception;

// Codice sbagliato, scaduto, già usato o con i tentativi finiti: un solo errore, così non si capisce quale dei casi
public class InvalidResetCodeException extends DomainException {
    public InvalidResetCodeException() {
        super("Invalid or expired reset code");
    }
}