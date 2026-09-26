package it.unical.geosentinel.shared.domain.exception;

import java.time.Duration;

public class RateLimitExceededException extends DomainException {
    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many attempts");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
