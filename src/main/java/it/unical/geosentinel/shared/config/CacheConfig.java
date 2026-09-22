package it.unical.geosentinel.shared.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {
    public static final String CACHE_PREFIX = "gs:cache:";
    public static final String TRAILS_CACHE = "trails";
    public static final String MAINTENANCE_BY_TRAIL_CACHE = "maintenance-by-trail";

    @Bean
    public RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(CACHE_PREFIX)
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();
    }

    // disableCreateOnMissingCache: una cache non registrata con typed(...) fallisce subito, invece di finire
    // sul serializer JDK di defaultCacheConfig() (deserializzazione Java da Redis = rischio gadget)
    @Bean
    public RedisCacheManagerBuilderCustomizer transactionAwareCaches() {
        return builder -> builder.transactionAware().disableCreateOnMissingCache();
    }

    public static RedisCacheConfiguration typed(RedisCacheConfiguration defaults, JsonMapper mapper,
                                                JavaType valueType, Duration ttl) {
        return defaults.entryTtl(ttl)
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, valueType)));
    }
}
