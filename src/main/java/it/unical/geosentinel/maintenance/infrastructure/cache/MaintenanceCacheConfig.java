// Configurazione Spring della cache Redis dei ticket di manutenzione per sentiero.
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

/**
 * Registra la cache {@code maintenance-by-trail} con il suo tipo e la sua durata. La registrazione è
 * necessaria: CacheConfig disabilita la creazione al volo delle cache, quindi una {@code @Cacheable} su una
 * cache non dichiarata fallirebbe.
 */
@Configuration
public class MaintenanceCacheConfig {

    /**
     * Aggiunge al cache manager di Redis la configurazione della cache dei ticket per sentiero: i valori sono
     * liste di {@code TicketView} serializzate in JSON e scadono dopo 5 minuti.
     *
     * @param defaults configurazione comune delle cache
     * @param jsonMapper mapper JSON dell'applicazione
     * @return il customizer da applicare al cache manager
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer maintenanceCaches(RedisCacheConfiguration defaults, JsonMapper jsonMapper) {
        return builder -> builder.withCacheConfiguration(CacheConfig.MAINTENANCE_BY_TRAIL_CACHE,
                CacheConfig.typed(defaults, jsonMapper,
                        jsonMapper.getTypeFactory().constructCollectionType(List.class, TicketView.class),
                        Duration.ofMinutes(5)));
    }
}
