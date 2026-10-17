package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {
    private static final String KEY_PREFIX = "rl:login:";
    // 10 tentativi ogni 15 minuti per account; fail-closed come il resto di /auth/**
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(10, 10.0 / 900, true);

    private final RedisRateLimiter redisRateLimiter;

    public RedisLoginAttemptLimiter(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }

    @Override
    public Optional<Duration> tryAcquire(String accountKey) {
        RateLimitDecision decision = redisRateLimiter.tryConsume(KEY_PREFIX + accountKey, POLICY);
        return decision.allowed() ? Optional.empty() : Optional.of(Duration.ofMillis(decision.retryAfterMillis()));
    }

    @Override
    public void reset(String accountKey) {
        redisRateLimiter.reset(KEY_PREFIX + accountKey);
    }
}
