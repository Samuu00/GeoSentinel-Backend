package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface MfaAttemptLimiter {
    // Optional.empty() = tentativo consentito; altrimenti quanto attendere
    Optional<Duration> tryAcquire(UUID userId);

    // Chiamato solo dopo un codice giusto: un utente valido non si blocca con i propri tentativi
    void reset(UUID userId);

    // Errori del login nell'ultima ora (contatore separato dal bucket); ritorna il conteggio dopo l'incremento
    long recordLoginFailure(UUID userId);
}