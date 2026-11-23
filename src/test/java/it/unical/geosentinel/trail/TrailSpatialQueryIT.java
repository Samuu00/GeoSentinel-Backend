// Test di integrazione delle query spaziali sui sentieri, eseguite su PostGIS in Testcontainers passando
// dalle rotte pubbliche. Controlla la ricerca per distanza (ST_DWithin espresso in metri grazie al cast a
// geography), la ricerca per riquadro (ST_Intersects con la bounding box) e il percorso semplificato.
// Serve anche contro le inversioni di latitudine e longitudine: se in qualche punto le due coordinate venissero
// scambiate, i test che si aspettano di trovare il sentiero fallirebbero.
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

/**
 * Verifica le ricerche {@code /trails/nearby} e {@code /trails?bbox=...} e la forma del percorso restituito.
 */
class TrailSpatialQueryIT extends AbstractIntegrationTest {

    /** Id di un sentiero da est a ovest lungo circa 1,7 km alla latitudine 39.30 (Sila), con x = lon e y = lat. */
    private UUID trailId;

    /** Inserisce il sentiero di riferimento prima di ogni test. */
    @BeforeEach
    void insertTrail() {
        trailId = insertTrail("Sentiero est-ovest", "LINESTRING(16.4800 39.3000, 16.5000 39.3000)");
    }

    /**
     * Un punto a circa 100 metri a nord del sentiero deve trovarlo con un raggio di 200 metri e non trovarlo con
     * un raggio di 50: la distanza è quindi misurata in metri e non in gradi.
     */
    @Test
    void pointAbout100MetersAwayIsFoundWith200MetersButNotWith50() {
        // 0.0009 gradi di latitudine corrispondono a circa 100 metri.
        assertThat(nearby(16.49, 39.3009, 200)).containsExactly(trailId);
        assertThat(nearby(16.49, 39.3009, 50)).isEmpty();
    }

    /**
     * Lo stesso punto con latitudine e longitudine scambiate cade nell'Oceano Indiano: anche con un raggio di
     * 50 km non deve trovare nulla.
     */
    @Test
    void swappingLatAndLonDoesNotFindTheTrail() {
        assertThat(nearby(39.3009, 16.49, 50_000)).isEmpty();
    }

    /**
     * La ricerca per riquadro deve restituire i sentieri che lo attraversano, anche solo in parte, e nessun
     * sentiero se il riquadro è accanto al tracciato senza toccarlo.
     */
    @Test
    void boundingBoxReturnsTrailsThatCrossIt() {
        assertThat(bbox("16.47,39.29,16.51,39.31")).containsExactly(trailId);
        // Il riquadro taglia il sentiero a metà, come succede ingrandendo la mappa: il sentiero deve restare visibile.
        assertThat(bbox("16.49,39.29,16.51,39.31")).containsExactly(trailId);
        // Riquadro accanto al sentiero, senza intersezione.
        assertThat(bbox("16.47,39.31,16.51,39.33")).isEmpty();
    }

    /**
     * Nella lista per riquadro ogni sentiero ha un percorso semplificato in GeoJSON. Per un sentiero rettilineo
     * di 21 punti allineati la semplificazione deve tenere solo i due estremi, nell'ordine [lon, lat].
     */
    @Test
    void summaryHasASimplifiedPathInLonLatOrder() {
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
        // In GeoJSON il primo valore di ogni posizione è la longitudine.
        assertThat(coordinates.get(0).get(0).asDouble()).isEqualTo(16.6);
        assertThat(coordinates.get(0).get(1).asDouble()).isEqualTo(39.4);
        assertThat(coordinates.get(1).get(0).asDouble()).isCloseTo(16.62, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(coordinates.get(1).get(1).asDouble()).isEqualTo(39.4);
    }

    /** Un riquadro con il minimo maggiore del massimo deve dare 400 con un corpo ProblemDetail. */
    @Test
    void invalidBoundingBoxIsA400ProblemDetail() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/trails?bbox=16.51,39.29,16.47,39.31", null, null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(isProblemJson(result)).isTrue();
    }

    /**
     * Cerca i sentieri entro un raggio da un punto.
     *
     * @param lon    la longitudine del punto
     * @param lat    la latitudine del punto
     * @param radius il raggio in metri
     * @return gli id dei sentieri trovati
     */
    private List<UUID> nearby(double lon, double lat, int radius) {
        HttpResult result = call(HttpMethod.GET,
                "/api/v1/trails/nearby?lon=" + lon + "&lat=" + lat + "&radius=" + radius, null, null);
        assertThat(result.status()).isEqualTo(200);
        return ids(result.body());
    }

    /**
     * Cerca i sentieri che intersecano un riquadro.
     *
     * @param bbox il riquadro nel formato {@code minLon,minLat,maxLon,maxLat}
     * @return gli id dei sentieri trovati
     */
    private List<UUID> bbox(String bbox) {
        HttpResult result = call(HttpMethod.GET, "/api/v1/trails?bbox=" + bbox, null, null);
        assertThat(result.status()).isEqualTo(200);
        return ids(result.body());
    }

    /**
     * Estrae gli id da una lista JSON di sentieri.
     *
     * @param body il corpo JSON della risposta
     * @return gli id nell'ordine della lista
     */
    private List<UUID> ids(String body) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode trail : json(body)) {
            ids.add(UUID.fromString(trail.get("id").asString()));
        }
        return ids;
    }
}
