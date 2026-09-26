package it.unical.geosentinel.trail.infrastructure.cache;

import it.unical.geosentinel.shared.config.CacheConfig;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

// Senza registrazione, @Cacheable("trails") fallisce: CacheConfig disabilita la creazione al volo delle cache
@Configuration
public class TrailCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer trailCaches(RedisCacheConfiguration defaults, JsonMapper jsonMapper) {
        return builder -> builder.withCacheConfiguration(CacheConfig.TRAILS_CACHE,
                CacheConfig.typed(defaults, jsonMapper, jsonMapper.constructType(TrailDetail.class), Duration.ofMinutes(10)));
    }
}
