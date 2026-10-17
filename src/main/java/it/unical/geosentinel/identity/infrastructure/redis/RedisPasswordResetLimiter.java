package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.PasswordResetLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisPasswordResetLimiter implements PasswordResetLimiter {
    private static final String KEY_PREFIX = "rl:pwreset::";
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(3, 3.0 / 3600, true);
    private final RedisRateLimiter redisRateLimiter;

    public RedisPasswordResetLimiter(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }

    @Override
    public Optional<Duration> tryAcquire(String accountKey) {
        RateLimitDecision decision = redisRateLimiter.tryConsume(KEY_PREFIX + accountKey, POLICY);
        return decision.allowed() ? Optional.empty() : Optional.of(Duration.ofMillis(decision.retryAfterMillis()));
    }
}
