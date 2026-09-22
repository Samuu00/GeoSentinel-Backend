package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
public class RedisTokenBlacklist implements TokenBlacklist {
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklist.class);
    private static final String KEY_PREFIX = "bl:jti:";
    private static final String REVOKED_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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

    @Override
    public boolean isRevoked(String jti) {
        if(jti == null || jti.isBlank()) return true;

        String key = KEY_PREFIX + jti;
        try{
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e){
            log.error("Redis unreachable while checking blacklist for JTI '{}'. Enforcing fail-closed policy.", jti, e);
            return true;
        }
    }
}
