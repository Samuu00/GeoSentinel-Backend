package it.unical.geosentinel.trail.infrastructure.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Indirizzo di GraphHopper nella rete del compose (ADR-0028) e tempi massimi: oltre, 503 routing-unavailable
@ConfigurationProperties("geosentinel.routing")
public record RoutingProperties(String url, String profile, Duration connectTimeout, Duration readTimeout) {
    public RoutingProperties {
        if (profile == null || profile.isBlank()) profile = "hike";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(10);
    }
}
