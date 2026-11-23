/*
 * Implementazione su Redis del limite dei codici della verifica in due passaggi (porta MfaAttemptLimiter),
 * con due token bucket per utente e un contatore degli errori del login per l'email d'avviso.
 */
package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.MfaAttemptLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Limiti dei codici di verifica per utente su Redis.
 */
@Component
public class RedisMfaAttemptLimiter implements MfaAttemptLimiter {
    /** Prefisso del bucket dei codici inseriti al login. */
    private static final String KEY_PREFIX = "rl:mfa:";
    /** Prefisso del bucket dei codici inseriti nelle operazioni in sessione. */
    private static final String SESSION_KEY_PREFIX = "rl:mfa-session:";
    /** Prefisso del contatore degli errori del login, usato per l'email d'avviso. */
    private static final String FAILURES_PREFIX = "mfa:fails:";
    /** 5 codici all'ora per account e per bucket, fail-closed come il login. */
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(5, 5.0 / 3600, true);

    private final RedisRateLimiter redisRateLimiter;
    private final StringRedisTemplate redis;

    public RedisMfaAttemptLimiter(RedisRateLimiter redisRateLimiter, StringRedisTemplate redis) {
        this.redisRateLimiter = redisRateLimiter;
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Duration> tryAcquireLogin(UUID userId) {
        return tryAcquire(KEY_PREFIX + userId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Duration> tryAcquireInSession(UUID userId) {
        return tryAcquire(SESSION_KEY_PREFIX + userId);
    }

    /** {@inheritDoc} */
    @Override
    public void reset(UUID userId) {
        redisRateLimiter.reset(KEY_PREFIX + userId);
        redisRateLimiter.reset(SESSION_KEY_PREFIX + userId);
    }

    /**
     * Consuma un gettone dal bucket indicato.
     *
     * @param key chiave Redis del bucket
     * @return vuoto se consentito, altrimenti l'attesa suggerita
     */
    private Optional<Duration> tryAcquire(String key) {
        RateLimitDecision decision = redisRateLimiter.tryConsume(key, POLICY);
        return decision.allowed() ? Optional.empty() : Optional.of(Duration.ofMillis(decision.retryAfterMillis()));
    }

    /**
     * Script Lua che incrementa il contatore e, al primo errore, gli dà una scadenza: la finestra dura un'ora
     * dal primo errore. INCR ed EXPIRE stanno in un unico script perché, con due comandi separati, un guasto
     * tra i due lascerebbe la chiave senza scadenza e il contatore oltre la soglia per sempre, e l'utente non
     * riceverebbe più avvisi.
     */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    /** {@inheritDoc} */
    @Override
    public long recordLoginFailure(UUID userId) {
        Long count = redis.execute(INCR_WITH_TTL, List.of(FAILURES_PREFIX + userId), String.valueOf(Duration.ofHours(1).toSeconds()));
        return count == null ? 0 : count;
    }
}
