// Configurazione della cache applicativa su Redis (annotazioni @Cacheable e @CacheEvict). Definisce i
// nomi delle cache, le impostazioni comuni e il metodo typed(...) con cui ogni feature registra la propria
// cache con un serializer JSON legato al tipo esatto del valore. In cache si salvano solo DTO, mai entità.
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

/**
 * Abilita il caching di Spring e configura il {@code RedisCacheManager} di Spring Boot.
 */
@Configuration
@EnableCaching
public class CacheConfig {
    /** Prefisso delle chiavi Redis delle cache, per distinguerle dalle altre chiavi (rate limit, blacklist). */
    public static final String CACHE_PREFIX = "gs:cache:";
    /** Cache del dettaglio dei sentieri per id, registrata in TrailCacheConfig. */
    public static final String TRAILS_CACHE = "trails";
    /** Cache dei ticket di manutenzione per sentiero, registrata in MaintenanceCacheConfig. */
    public static final String MAINTENANCE_BY_TRAIL_CACHE = "maintenance-by-trail";

    /**
     * Configurazione di base da cui partono tutte le cache: prefisso delle chiavi, durata di 10 minuti e
     * nessun valore {@code null} salvato.
     *
     * @return la configurazione comune
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(CACHE_PREFIX)
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();
    }

    /**
     * Rende le cache consapevoli delle transazioni (scritture ed evict avvengono solo dopo il commit) e
     * vieta la creazione automatica delle cache non registrate. Una cache non registrata con
     * {@link #typed} fallisce subito invece di usare il serializer JDK della configurazione di default:
     * deserializzare oggetti Java letti da Redis esporrebbe l'applicazione ad attacchi con gadget chain.
     *
     * @return il customizer applicato al builder del cache manager
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer transactionAwareCaches() {
        return builder -> builder.transactionAware().disableCreateOnMissingCache();
    }

    /**
     * Crea la configurazione di una cache con un serializer JSON legato a un solo tipo. Senza il default
     * typing di Jackson il JSON salvato non contiene nomi di classi, e in lettura si può costruire solo
     * il tipo indicato.
     *
     * @param defaults  configurazione di base ({@link #defaultCacheConfiguration()})
     * @param mapper    mapper JSON dell'applicazione
     * @param valueType tipo dei valori della cache
     * @param ttl       durata delle voci
     * @return la configurazione da registrare con {@code withCacheConfiguration}
     */
    public static RedisCacheConfiguration typed(RedisCacheConfiguration defaults, JsonMapper mapper,
                                                JavaType valueType, Duration ttl) {
        return defaults.entryTtl(ttl)
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, valueType)));
    }
}
