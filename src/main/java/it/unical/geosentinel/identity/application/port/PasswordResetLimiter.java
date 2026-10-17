package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;

public interface PasswordResetLimiter {
    Optional<Duration> tryAcquire(String accountKey);
}
