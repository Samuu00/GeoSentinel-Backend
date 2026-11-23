/*
 * Esito di un controllo di rate limit fatto da RedisRateLimiter con l'algoritmo del token bucket.
 */
package it.unical.geosentinel.identity.infrastructure.redis;

/**
 * Decisione del limitatore.
 *
 * @param allowed se la richiesta può passare
 * @param remainingTokens gettoni rimasti nel bucket dopo la richiesta
 * @param retryAfterMillis per una richiesta rifiutata, millisecondi da attendere prima di riprovare
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterMillis) {
    /**
     * @param remaining gettoni rimasti
     * @return una decisione positiva
     */
    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    /**
     * @param retryAfterMillis attesa suggerita in millisecondi
     * @return una decisione negativa
     */
    public static RateLimitDecision rejected(long retryAfterMillis) {
        return new RateLimitDecision(false, 0, retryAfterMillis);
    }
}
