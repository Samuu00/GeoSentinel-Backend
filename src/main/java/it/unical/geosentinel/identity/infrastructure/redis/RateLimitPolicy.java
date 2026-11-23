/*
 * Parametri di un token bucket: quante richieste si possono fare di fila, a che ritmo si ricaricano e come
 * comportarsi se Redis non risponde.
 */
package it.unical.geosentinel.identity.infrastructure.redis;

/**
 * Politica di rate limit.
 *
 * @param capacity numero massimo di gettoni nel bucket, cioè di richieste consecutive ammesse
 * @param refillPerSecond gettoni aggiunti al secondo
 * @param failClosed se {@code true}, quando Redis non risponde la richiesta viene rifiutata invece che lasciata passare
 */
public record RateLimitPolicy(long capacity, double refillPerSecond, boolean failClosed) {
    /**
     * Valida i parametri. Una ricarica pari a zero farebbe dividere per zero lo script Lua, con TTL e
     * Retry-After infiniti.
     *
     * @throws IllegalArgumentException se capacità o ricarica non sono positive
     */
    public RateLimitPolicy {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (!(refillPerSecond > 0)) throw new IllegalArgumentException("refillPerSecond must be > 0");
    }

    /**
     * @param maxRequests richieste ammesse al minuto
     * @return una politica fail-open che ricarica {@code maxRequests} gettoni al minuto
     */
    public static RateLimitPolicy perMinute(long maxRequests) {
        return new RateLimitPolicy(maxRequests, (double) maxRequests / 60.0, false);
    }

    /**
     * @param maxRequests richieste ammesse al secondo
     * @return una politica fail-open che ricarica {@code maxRequests} gettoni al secondo
     */
    public static RateLimitPolicy perSecond(long maxRequests) {
        return new RateLimitPolicy(maxRequests, (double) maxRequests, false);
    }

    /**
     * Restituisce la stessa politica in versione fail-closed. Si usa per le rotte che proteggono dal brute
     * force (degli endpoint sotto {@code /auth}): durante un guasto di Redis è meglio rispondere 429 che non avere alcun limite.
     *
     * @return la politica con {@code failClosed = true}
     */
    public RateLimitPolicy withFailClosed() {
        return new RateLimitPolicy(capacity, refillPerSecond, true);
    }
}
