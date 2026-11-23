// Proprietà di configurazione del motore di percorso, lette dal prefisso geosentinel.routing: indirizzo
// del servizio GraphHopper nella rete di Docker Compose, profilo da usare e tempi massimi di connessione e
// lettura. Oltre questi tempi la richiesta fallisce con un 503 routing-unavailable.
package it.unical.geosentinel.trail.infrastructure.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Impostazioni del client verso GraphHopper.
 *
 * @param url            indirizzo base del servizio
 * @param profile        profilo di GraphHopper, di default {@code hike}
 * @param connectTimeout tempo massimo per aprire la connessione, di default 2 secondi
 * @param readTimeout    tempo massimo di attesa della risposta, di default 10 secondi
 */
@ConfigurationProperties("geosentinel.routing")
public record RoutingProperties(String url, String profile, Duration connectTimeout, Duration readTimeout) {
    /** Applica i valori di default ai campi non impostati. */
    public RoutingProperties {
        if (profile == null || profile.isBlank()) profile = "hike";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(10);
    }
}
