package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import it.unical.geosentinel.trail.application.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// GET /trails/route (ADR-0027): rotta pubblica, 422 no-route, rete rifatta quando un sentiero si chiude.
// Coordinate lontane dagli altri test (Alpi): la rete contiene tutti i sentieri non chiusi del database
class TrailRouteIT extends AbstractIntegrationTest {

    @Autowired
    private RouteService routeService;

    private UUID first;
    private UUID second;

    @BeforeEach
    void insertTrails() {
        // due sentieri che condividono il vertice 10.010 45.000
        first = insertTrail("Primo tratto", "LINESTRING(10.000 45.000, 10.005 45.000, 10.010 45.000)");
        second = insertTrail("Secondo tratto", "LINESTRING(10.010 45.000, 10.010 45.015, 10.010 45.030)");
        // inseriti con l'SQL, senza TrailService: la rete va rifatta a mano
        routeService.onTrailsChanged(new RouteService.TrailsChanged());
    }

    @Test
    void guestGetsTheRouteAcrossBothTrails() {
        HttpResult result = route(10.000, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(200);
        // la richiesta rivela la posizione: niente cache né ETag, a differenza del resto del catalogo
        assertThat(result.headers().getCacheControl()).contains("no-store");
        assertThat(result.headers().getETag()).isNull();
        JsonNode body = json(result.body());
        assertThat(body.get("trailIds").get(0).asString()).isEqualTo(first.toString());
        assertThat(body.get("trailIds").get(1).asString()).isEqualTo(second.toString());
        // [lon, lat], dalla partenza all'arrivo
        JsonNode coordinates = body.get("path").get("coordinates");
        assertThat(coordinates.get(0).get(0).asDouble()).isEqualTo(10.000);
        assertThat(coordinates.get(coordinates.size() - 1).get(1).asDouble()).isEqualTo(45.030);
        assertThat(body.get("lengthM").asDouble()).isBetween(4050.0, 4200.0);
    }

    @Test
    void pointFarFromTheTrailsIsA422NoRoute() {
        HttpResult result = route(10.000, 45.000, 11.000, 46.000);

        assertThat(result.status()).isEqualTo(422);
        assertThat(isProblemJson(result)).isTrue();
        assertThat(json(result.body()).get("type").asString()).endsWith("/no-route");
    }

    @Test
    void closingATrailRemovesItFromTheNetwork() {
        assertThat(route(10.000, 45.000, 10.010, 45.030).status()).isEqualTo(200);

        HttpResult closed = call(HttpMethod.PATCH, "/api/v1/trails/" + second + "/status",
                obtainToken(Role.PARK_MANAGER), "{\"status\":\"CLOSED\"}");
        assertThat(closed.status()).isEqualTo(200);

        // l'arrivo resta entro 50 km dal primo sentiero: il percorso c'è ancora, ma senza il sentiero chiuso
        HttpResult after = route(10.000, 45.000, 10.010, 45.030);
        assertThat(after.status()).isEqualTo(200);
        assertThat(json(after.body()).get("trailIds").toString()).doesNotContain(second.toString());
    }

    private HttpResult route(double fromLon, double fromLat, double toLon, double toLat) {
        return call(HttpMethod.GET, "/api/v1/trails/route?fromLon=" + fromLon + "&fromLat=" + fromLat
                + "&toLon=" + toLon + "&toLat=" + toLat, null, null);
    }
}
