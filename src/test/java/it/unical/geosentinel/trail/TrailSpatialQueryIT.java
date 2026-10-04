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

// ST_DWithin in METRI (cast ::geography) e ST_Intersects sulla bbox (ADR-0020), passando dalle rotte pubbliche.
// Regressione lon/lat (ADR-0010): se da qualche parte lat e lon si invertissero, i test "trovato" fallirebbero
class TrailSpatialQueryIT extends AbstractIntegrationTest {

    // Sentiero est-ovest di ~1.7 km a lat 39.30 (Sila): x = lon, y = lat
    private UUID trailId;

    @BeforeEach
    void insertTrail() {
        trailId = insertTrail("Sentiero est-ovest", "LINESTRING(16.4800 39.3000, 16.5000 39.3000)");
    }

    @Test
    void pointAbout100MetersAwayIsFoundWith200MetersButNotWith50() {
        // 0.0009° di latitudine ~ 100 m a nord del sentiero
        assertThat(nearby(16.49, 39.3009, 200)).containsExactly(trailId);
        assertThat(nearby(16.49, 39.3009, 50)).isEmpty();
    }

    @Test
    void swappingLatAndLonDoesNotFindTheTrail() {
        // lo stesso punto con le coordinate invertite cade nell'Oceano Indiano
        assertThat(nearby(39.3009, 16.49, 50_000)).isEmpty();
    }

    @Test
    void boundingBoxReturnsTrailsThatCrossIt() {
        assertThat(bbox("16.47,39.29,16.51,39.31")).containsExactly(trailId);
        // la bbox taglia il sentiero a metà: con lo zoom sulla mappa il sentiero deve restare visibile
        assertThat(bbox("16.49,39.29,16.51,39.31")).containsExactly(trailId);
        // bbox accanto al sentiero, senza toccarlo
        assertThat(bbox("16.47,39.31,16.51,39.33")).isEmpty();
    }

    @Test
    void summaryHasASimplifiedPathInLonLatOrder() {
        // 21 punti allineati: la semplificazione tiene solo gli estremi
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i <= 20; i++) {
            wkt.append(i == 0 ? "" : ", ").append(16.6000 + i * 0.001).append(" 39.4000");
        }
        UUID straight = insertTrail("Sentiero dritto", wkt.append(")").toString());

        HttpResult result = call(HttpMethod.GET, "/api/v1/trails?bbox=16.59,39.39,16.63,39.41", null, null);
        assertThat(result.status()).isEqualTo(200);
        JsonNode trail = json(result.body()).get(0);
        assertThat(UUID.fromString(trail.get("id").asString())).isEqualTo(straight);

        JsonNode coordinates = trail.get("path").get("coordinates");
        assertThat(trail.get("path").get("type").asString()).isEqualTo("LineString");
        assertThat(coordinates.size()).isEqualTo(2);
        // [lon, lat]: il primo valore è la longitudine (ADR-0010)
        assertThat(coordinates.get(0).get(0).asDouble()).isEqualTo(16.6);
        assertThat(coordinates.get(0).get(1).asDouble()).isEqualTo(39.4);
        assertThat(coordinates.get(1).get(0).asDouble()).isCloseTo(16.62, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(coordinates.get(1).get(1).asDouble()).isEqualTo(39.4);
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
