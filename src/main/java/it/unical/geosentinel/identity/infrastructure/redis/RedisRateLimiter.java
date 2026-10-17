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

@Component
public class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    // Retry-After suggerito quando Redis non risponde e la policy è fail-closed
    private static final long UNAVAILABLE_RETRY_AFTER_MILLIS = 1000;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        this.tokenBucketScript.setResultType(List.class);
    }

    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy) { return tryConsume(key, policy, 1); }

    // L'orario lo legge lo script da Redis (TIME): tutte le istanze usano lo stesso orologio
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy, int requestedTokens) {
        Objects.requireNonNull(key, "Rate limit key cannot be null");
        Objects.requireNonNull(policy, "Rate limit policy cannot be null");
        // oltre la capacità il bucket non si riempirebbe mai: Retry-After infinito
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

            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            long retryAfterMillis = result.get(2);

            return new RateLimitDecision(allowed, remaining, retryAfterMillis);

        } catch (Exception e) {
            log.error("Redis error during rate limiting evaluation for key '{}'", key, e);
            return unavailable(policy);
        }
    }

    // Cancella il bucket: alla prossima richiesta lo script lo ricrea pieno. Best effort: con Redis giù il bucket
    // si svuota comunque da solo al ritmo della policy
    public void reset(String key) {
        Objects.requireNonNull(key, "Rate limit key cannot be null");
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis error while resetting rate limit key '{}'", key, e);
        }
    }

    // Redis non disponibile: fail-open di default (niente denial of service per un guasto del limiter),
    // fail-closed per le policy che proteggono dal brute force
    private static RateLimitDecision unavailable(RateLimitPolicy policy) {
        return policy.failClosed()
                ? RateLimitDecision.rejected(UNAVAILABLE_RETRY_AFTER_MILLIS)
                : RateLimitDecision.allowed(policy.capacity());
    }
}
