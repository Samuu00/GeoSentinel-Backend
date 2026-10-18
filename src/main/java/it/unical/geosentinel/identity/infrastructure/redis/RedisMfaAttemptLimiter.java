package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.MfaAttemptLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisMfaAttemptLimiter implements MfaAttemptLimiter {
    private static final String KEY_PREFIX = "rl:mfa:";
    private static final String FAILURES_PREFIX = "mfa:fails:";
    // 5 codici sbagliati all'ora per account; fail-closed come il login
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(5, 5.0 / 3600, true);

    private final RedisRateLimiter redisRateLimiter;
    private final StringRedisTemplate redis;

    public RedisMfaAttemptLimiter(RedisRateLimiter redisRateLimiter, StringRedisTemplate redis) {
        this.redisRateLimiter = redisRateLimiter;
        this.redis = redis;
    }

    @Override
    public Optional<Duration> tryAcquire(UUID userId) {
        RateLimitDecision decision = redisRateLimiter.tryConsume(KEY_PREFIX + userId, POLICY);
        return decision.allowed() ? Optional.empty() : Optional.of(Duration.ofMillis(decision.retryAfterMillis()));
    }

    @Override
    public void reset(UUID userId) {
        redisRateLimiter.reset(KEY_PREFIX + userId);
    }

    // Finestra di un'ora dal primo errore. INCR + EXPIRE in uno script: con due comandi separati, un guasto tra i due
    // lascerebbe la chiave senza TTL e il contatore oltre 3 per sempre (nessun avviso più per quell'utente)
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    @Override
    public long recordLoginFailure(UUID userId) {
        Long count = redis.execute(INCR_WITH_TTL, List.of(FAILURES_PREFIX + userId), String.valueOf(Duration.ofHours(1).toSeconds()));
        return count == null ? 0 : count;
    }
}