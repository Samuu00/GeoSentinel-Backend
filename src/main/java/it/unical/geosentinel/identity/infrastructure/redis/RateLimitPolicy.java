package it.unical.geosentinel.identity.infrastructure.redis;

// failClosed: se Redis non risponde la richiesta viene rifiutata invece che lasciata passare
public record RateLimitPolicy(long capacity, double refillPerSecond, boolean failClosed) {
    // refill = 0 farebbe dividere per zero nello script Lua (TTL e Retry-After infiniti)
    public RateLimitPolicy {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (!(refillPerSecond > 0)) throw new IllegalArgumentException("refillPerSecond must be > 0");
    }

    public static RateLimitPolicy perMinute(long maxRequests) {
        return new RateLimitPolicy(maxRequests, (double) maxRequests / 60.0, false);
    }

    public static RateLimitPolicy perSecond(long maxRequests) {
        return new RateLimitPolicy(maxRequests, (double) maxRequests, false);
    }

    // Per le rotte anti brute-force (/auth/**): meglio un 429 durante un guasto di Redis che nessun limite
    public RateLimitPolicy withFailClosed() {
        return new RateLimitPolicy(capacity, refillPerSecond, true);
    }
}
