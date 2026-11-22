package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import it.unical.geosentinel.trail.application.RoutingEngine;
import it.unical.geosentinel.trail.application.RoutingEngine.EngineRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// GET /trails/route (ADR-0028): il motore (GraphHopper) è finto; qui contano la rotta pubblica, i sentieri del parco
// ricavati dal tracciato, le aree dei sentieri chiusi mandate al motore e gli errori. Coordinate lontane dagli altri
// test (Alpi)
class TrailRouteIT extends AbstractIntegrationTest {

    @MockitoBean
    private RoutingEngine routingEngine;

    private UUID first;
    private UUID second;

    // il tracciato che "trova il motore": lungo i due sentieri, con uno scarto di qualche metro come tra OSM e database
    private final EngineRoute alongBoth = new EngineRoute(List.of(
            List.of(10.000, 45.00005), List.of(10.005, 45.00005), List.of(10.010, 45.00005),
            List.of(10.01005, 45.015), List.of(10.01005, 45.030)), 4_100);

    @BeforeEach
    void insertTrails() {
        first = insertTrail("Primo tratto", "LINESTRING(10.000 45.000, 10.005 45.000, 10.010 45.000)");
        second = insertTrail("Secondo tratto", "LINESTRING(10.010 45.000, 10.010 45.015, 10.010 45.030)");
    }

    @Test
    void guestGetsTheEngineRouteWithTheTrailsItFollowsInOrder() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.of(alongBoth));

        HttpResult result = route(10.000, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(200);
        // la richiesta rivela la posizione: niente cache né ETag, a differenza del resto del catalogo
        assertThat(result.headers().getCacheControl()).contains("no-store");
        assertThat(result.headers().getETag()).isNull();
        JsonNode body = json(result.body());
        assertThat(body.get("trailIds")).hasSize(2);
        assertThat(body.get("trailIds").get(0).asString()).isEqualTo(first.toString());
        assertThat(body.get("trailIds").get(1).asString()).isEqualTo(second.toString());
        JsonNode coordinates = body.get("path").get("coordinates");
        assertThat(coordinates.get(0).get(0).asDouble()).isEqualTo(10.000);
        assertThat(coordinates.get(coordinates.size() - 1).get(1).asDouble()).isEqualTo(45.030);
        assertThat(body.get("lengthM").asDouble()).isEqualTo(4_100);
        // partenza e arrivo arrivano al motore come sono, in lon/lat
        verify(routingEngine).route(eq(10.000), eq(45.000), eq(10.010), eq(45.030), any());
    }

    @Test
    void closedTrailIsSentAsAnAreaToAvoidAndLeftOutOfTheTrails() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.of(alongBoth));
        HttpResult closed = call(HttpMethod.PATCH, "/api/v1/trails/" + second + "/status",
                obtainToken(Role.PARK_MANAGER), "{\"status\":\"CLOSED\"}");
        assertThat(closed.status()).isEqualTo(200);

        HttpResult after = route(10.000, 45.000, 10.010, 45.030);

        assertThat(after.status()).isEqualTo(200);
        assertThat(json(after.body()).get("trailIds").toString()).doesNotContain(second.toString());
        ArgumentCaptor<String> avoid = ArgumentCaptor.forClass(String.class);
        verify(routingEngine).route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), avoid.capture());
        JsonNode area = json(avoid.getValue());
        assertThat(area.get("type").asString()).isEqualTo("MultiPolygon");
        // l'unione può contenere sentieri chiusi di altri test: basta che copra quello chiuso qui ([lon, lat])
        assertThat(avoid.getValue()).contains("[10.01");
    }

    @Test
    void noRouteFromTheEngineIsA422NoRoute() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());

        HttpResult result = route(10.000, 45.000, 11.000, 46.000);

        assertThat(result.status()).isEqualTo(422);
        assertThat(isProblemJson(result)).isTrue();
        assertThat(json(result.body()).get("type").asString()).endsWith("/no-route");
    }

    @Test
    void engineDownIsA503WithRetryAfter() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenThrow(new RoutingUnavailableException("down", null));

        HttpResult result = route(10.000, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.headers().getFirst("Retry-After")).isEqualTo("5");
        assertThat(json(result.body()).get("type").asString()).endsWith("/routing-unavailable");
    }

    private HttpResult route(double fromLon, double fromLat, double toLon, double toLat) {
        return call(HttpMethod.GET, "/api/v1/trails/route?fromLon=" + fromLon + "&fromLat=" + fromLat
                + "&toLon=" + toLon + "&toLat=" + toLat, null, null);
    }
}
