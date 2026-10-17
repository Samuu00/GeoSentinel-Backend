package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;

public interface LoginAttemptLimiter {
    // Optional.empty() = tentativo consentito; altrimenti quanto attendere prima di riprovare
    Optional<Duration> tryAcquire(String accountKey);

    // Bucket di nuovo pieno (dopo un reset della password riuscito)
    void reset(String accountKey);
}
