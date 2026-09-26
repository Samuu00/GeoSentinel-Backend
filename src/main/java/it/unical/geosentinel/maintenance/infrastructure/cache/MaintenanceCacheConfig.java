package it.unical.geosentinel.maintenance.infrastructure.cache;

import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.shared.config.CacheConfig;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

// Senza registrazione, @Cacheable("maintenance-by-trail") fallisce: CacheConfig disabilita la creazione al volo delle cache
@Configuration
public class MaintenanceCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer maintenanceCaches(RedisCacheConfiguration defaults, JsonMapper jsonMapper) {
        return builder -> builder.withCacheConfiguration(CacheConfig.MAINTENANCE_BY_TRAIL_CACHE,
                CacheConfig.typed(defaults, jsonMapper,
                        jsonMapper.getTypeFactory().constructCollectionType(List.class, TicketView.class),
                        Duration.ofMinutes(5)));
    }
}
