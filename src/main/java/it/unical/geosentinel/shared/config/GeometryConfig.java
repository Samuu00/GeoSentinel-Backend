package it.unical.geosentinel.shared.config;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class GeometryConfig {
    public static final int SRID_WGS84 = 4326;

    @Bean
    public GeometryFactory geometryFactory() {
        return new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
