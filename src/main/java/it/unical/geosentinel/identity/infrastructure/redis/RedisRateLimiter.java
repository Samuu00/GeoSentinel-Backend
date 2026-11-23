/*
 * Motore di rate limit condiviso da tutti i limitatori del backend. Implementa un token bucket con uno script
 * Lua eseguito in modo atomico su Redis, così il limite vale anche con più istanze del backend.
 */
package it.unical.geosentinel.identity.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Token bucket su Redis. Lo script {@code scripts/token_bucket.lua} legge il bucket, lo ricarica in base al
 * tempo trascorso, consuma i gettoni richiesti e restituisce l'esito, tutto in un'unica operazione atomica.
 * Lo usano {@code RateLimitFilter} e i limitatori per account.
 */
@Component
public class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    /** Retry-After suggerito quando Redis non risponde e la politica è fail-closed. */
    private static final long UNAVAILABLE_RETRY_AFTER_MILLIS = 1000;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        this.tokenBucketScript.setResultType(List.class);
    }

    /**
     * Consuma un gettone.
     *
     * @param key chiave Redis del bucket
     * @param policy politica da applicare
     * @return la decisione
     */
    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy) { return tryConsume(key, policy, 1); }

    /**
     * Consuma uno o più gettoni. L'orario lo legge lo script da Redis (comando TIME), così tutte le istanze del
     * backend usano lo stesso orologio. Se Redis non risponde o lo script restituisce un risultato inatteso, la
     * decisione dipende da {@link RateLimitPolicy#failClosed()}.
     *
     * @param key chiave Redis del bucket
     * @param policy politica da applicare
     * @param requestedTokens gettoni da consumare
     * @return la decisione
     * @throws IllegalArgumentException se {@code requestedTokens} non è tra 1 e la capacità
     */
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy, int requestedTokens) {
        Objects.requireNonNull(key, "Rate limit key cannot be null");
        Objects.requireNonNull(policy, "Rate limit policy cannot be null");
        // Oltre la capacità il bucket non avrebbe mai abbastanza gettoni: il Retry-After sarebbe infinito
        if (requestedTokens < 1 || requestedTokens > policy.capacity()) {
            throw new IllegalArgumentException("requestedTokens must be between 1 and capacity");
        }

        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(key),
                    String.valueOf(policy.capacity()),
                    String.valueOf(policy.refillPerSecond()),
                    String.valueOf(requestedTokens)
            );

            if (result == null || result.size() < 3) {
                log.warn("Invalid Lua response for key '{}'", key);
                return unavailable(policy);
            }

            // Lo script restituisce [consentito (1 o 0), gettoni rimasti, attesa in millisecondi]
            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            long retryAfterMillis = result.get(2);

            return new RateLimitDecision(allowed, remaining, retryAfterMillis);

        } catch (Exception e) {
            log.error("Redis error during rate limiting evaluation for key '{}'", key, e);
            return unavailable(policy);
        }
    }

    /**
     * Cancella il bucket: alla richiesta successiva lo script lo ricrea pieno. È best effort: se Redis non
     * risponde l'errore si registra e basta, perché il bucket si ricarica comunque da solo al ritmo della
     * politica.
     *
     * @param key chiave Redis del bucket
     */
    public void reset(String key) {
        Objects.requireNonNull(key, "Rate limit key cannot be null");
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis error while resetting rate limit key '{}'", key, e);
        }
    }

    /**
     * Decisione da prendere quando Redis non è disponibile: di norma si lascia passare (fail-open), perché un
     * guasto del limitatore non deve bloccare tutto il servizio; le politiche che proteggono dal brute force
     * invece rifiutano (fail-closed).
     *
     * @param policy politica in uso
     * @return la decisione di ripiego
     */
    private static RateLimitDecision unavailable(RateLimitPolicy policy) {
        return policy.failClosed()
                ? RateLimitDecision.rejected(UNAVAILABLE_RETRY_AFTER_MILLIS)
                : RateLimitDecision.allowed(policy.capacity());
    }
}
