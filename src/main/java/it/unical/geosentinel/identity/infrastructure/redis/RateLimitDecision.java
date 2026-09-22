package it.unical.geosentinel.identity.infrastructure.redis;

public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterMillis) {
    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    public static RateLimitDecision rejected(long retryAfterMillis) {
        return new RateLimitDecision(false, 0, retryAfterMillis);
    }
}