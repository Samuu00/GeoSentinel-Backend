package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.infrastructure.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class RedisTokenBlacklist implements TokenBlacklist {
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklist.class);
    private static final String KEY_PREFIX = "bl:jti:";
    private static final String USER_KEY_PREFIX = "bl:user:";
    private static final String REVOKED_VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final Duration accessTtl;

    public RedisTokenBlacklist(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.accessTtl = jwtProperties.accessTtl();
    }

    @Override
    public void revoke(String jti, Duration ttl) {
        Objects.requireNonNull(jti, "jti cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        if(ttl.isNegative() || ttl.isZero()) return;

        String key = KEY_PREFIX + jti;
        try{
            redisTemplate.opsForValue().set(key, REVOKED_VALUE, ttl);
        } catch (Exception e) {
            log.error("Failed to blacklist JTI '{}' in Redis", jti, e);
            throw new IllegalStateException("Could not write token revocation to store", e);
        }
    }

    // TTL = durata di un access token: dopo, quelli emessi fino a 'until' sono scaduti comunque
    @Override
    public void revokeAllIssuedUntil(UUID userId, Instant until) {
        try {
            redisTemplate.opsForValue().set(USER_KEY_PREFIX + userId, String.valueOf(until.getEpochSecond()), accessTtl);
        } catch (Exception e) {
            log.error("Failed to revoke access tokens of user '{}' in Redis", userId, e);
            throw new IllegalStateException("Could not write token revocation to store", e);
        }
    }

    // Un solo round trip (MGET) per jti e revoca per utente
    @Override
    public boolean isRevoked(String jti, String subject, Instant issuedAt) {
        if (jti == null || jti.isBlank() || subject == null || issuedAt == null) return true;

        try {
            List<String> values = redisTemplate.opsForValue().multiGet(List.of(KEY_PREFIX + jti, USER_KEY_PREFIX + subject));
            if (values == null || values.get(0) != null) {
                return true;
            }
            String revokedUntil = values.get(1);
            return revokedUntil != null && issuedAt.getEpochSecond() <= Long.parseLong(revokedUntil);
        } catch (Exception e) {
            log.error("Redis unreachable while checking blacklist for JTI '{}'. Enforcing fail-closed policy.", jti, e);
            return true;
        }
    }
}
