// Configurazione della cache Redis dei sentieri. La cache va registrata esplicitamente con il suo tipo:
// CacheConfig disabilita la creazione automatica delle cache, quindi senza questa classe
// @Cacheable sul dettaglio dei sentieri fallirebbe.
package it.unical.geosentinel.trail.infrastructure.cache;

import it.unical.geosentinel.shared.config.CacheConfig;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Registra la cache dei dettagli dei sentieri con un serializer JSON per {@link TrailDetail}.
 */
@Configuration
public class TrailCacheConfig {

    /**
     * @param defaults   configurazione comune delle cache
     * @param jsonMapper mapper JSON dell'applicazione
     * @return il customizer che aggiunge la cache dei sentieri, con durata di 10 minuti
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer trailCaches(RedisCacheConfiguration defaults, JsonMapper jsonMapper) {
        return builder -> builder.withCacheConfiguration(CacheConfig.TRAILS_CACHE,
                CacheConfig.typed(defaults, jsonMapper, jsonMapper.constructType(TrailDetail.class), Duration.ofMinutes(10)));
    }
}
