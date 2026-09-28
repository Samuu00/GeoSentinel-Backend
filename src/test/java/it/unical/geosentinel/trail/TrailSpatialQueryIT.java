package it.unical.geosentinel.trail;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// ST_DWithin in METRI (cast ::geography) e ST_Contains sulla bbox, passando dalle rotte pubbliche.
// Regressione lon/lat (D10): se da qualche parte lat e lon si invertissero, i test "trovato" fallirebbero
class TrailSpatialQueryIT extends AbstractIntegrationTest {

    // Sentiero est-ovest di ~1.7 km a lat 39.30 (Sila): x = lon, y = lat
    private UUID trailId;

    @BeforeEach
    void insertTrail() {
        trailId = insertTrail("Sentiero est-ovest", "LINESTRING(16.4800 39.3000, 16.5000 39.3000)");
    }

    @Test
    void pointAbout100MetersAwayIsFoundWith200MetersButNotWith50() {
        // 0.0009° di latitudine ≈ 100 m a nord del sentiero
        assertThat(nearby(16.49, 39.3009, 200)).containsExactly(trailId);
        assertThat(nearby(16.49, 39.3009, 50)).isEmpty();
    }

    @Test
    void swappingLatAndLonDoesNotFindTheTrail() {
        // lo stesso punto con le coordinate invertite cade nell'Oceano Indiano
        assertThat(nearby(39.3009, 16.49, 50_000)).isEmpty();
    }

    @Test
    void boundingBoxReturnsOnlyTrailsEntirelyInside() {
        assertThat(bbox("16.47,39.29,16.51,39.31")).containsExactly(trailId);
        // la bbox taglia il sentiero a metà: ST_Contains lo esclude
        assertThat(bbox("16.49,39.29,16.51,39.31")).isEmpty();
    }

    @Test
    void invalidBoundingBoxIsA400ProblemDetail() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/trails?bbox=16.51,39.29,16.47,39.31", null, null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(isProblemJson(result)).isTrue();
    }

    private List<UUID> nearby(double lon, double lat, int radius) {
        HttpResult result = call(HttpMethod.GET,
                "/api/v1/trails/nearby?lon=" + lon + "&lat=" + lat + "&radius=" + radius, null, null);
        assertThat(result.status()).isEqualTo(200);
        return ids(result.body());
    }

    private List<UUID> bbox(String bbox) {
        HttpResult result = call(HttpMethod.GET, "/api/v1/trails?bbox=" + bbox, null, null);
        assertThat(result.status()).isEqualTo(200);
        return ids(result.body());
    }

    private List<UUID> ids(String body) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode trail : json(body)) {
            ids.add(UUID.fromString(trail.get("id").asString()));
        }
        return ids;
    }
}
