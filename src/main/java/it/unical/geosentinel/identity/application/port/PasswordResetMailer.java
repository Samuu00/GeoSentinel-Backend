package it.unical.geosentinel.identity.application.port;

import java.time.Duration;

// Invio del codice di reset della password
public interface PasswordResetMailer {
    void sendCode(String email, String code, Duration validFor);
}
