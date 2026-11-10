package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

// Due bucket per account (ADR-0026): chi conosce solo la password svuota quello del login, ma non blocca il titolare
// nelle operazioni in sessione (cambio password, disattivazione, codici di recupero)
public interface MfaAttemptLimiter {
    // Optional.empty() = tentativo consentito; altrimenti quanto attendere
    Optional<Duration> tryAcquireLogin(UUID userId);

    Optional<Duration> tryAcquireInSession(UUID userId);

    // Azzera entrambi i bucket: dopo un codice giusto, un reset della password o un reset da ADMIN
    void reset(UUID userId);

    // Errori del login nell'ultima ora (contatore separato dal bucket); ritorna il conteggio dopo l'incremento
    long recordLoginFailure(UUID userId);
}
