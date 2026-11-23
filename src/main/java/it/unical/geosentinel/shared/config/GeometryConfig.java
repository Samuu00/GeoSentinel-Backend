// Configurazione dei bean di base condivisi da tutte le feature: la GeometryFactory con cui si creano
// tutte le geometrie JTS (sempre con SRID 4326, cioè coordinate WGS 84 in gradi) e il Clock usato al
// posto di Instant.now(), così che nei test l'ora corrente si possa fissare.
package it.unical.geosentinel.shared.config;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Definisce i bean {@link GeometryFactory} e {@link Clock}.
 */
@Configuration
public class GeometryConfig {
    /** Codice EPSG del sistema di riferimento WGS 84 (latitudine e longitudine in gradi), usato da PostGIS. */
    public static final int SRID_WGS84 = 4326;

    /**
     * @return la factory con precisione di default (doppia precisione) e SRID 4326, da usare per ogni
     *         geometria creata nell'applicazione
     */
    @Bean
    public GeometryFactory geometryFactory() {
        return new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    }

    /**
     * @return l'orologio di sistema in UTC, iniettato nei bean che hanno bisogno dell'ora corrente
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
