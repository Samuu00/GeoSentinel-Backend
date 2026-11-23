// Test di integrazione della rotta GET /trails/route, che calcola un percorso a piedi tra due punti, con
// chiamate HTTP vere e PostGIS in Testcontainers. Il motore di routing (GraphHopper) è sostituito da un mock:
// qui contano la rotta pubblica, l'elenco dei sentieri del parco ricavato dal tracciato, le aree dei sentieri
// chiusi passate al motore e la gestione degli errori. Le coordinate sono nelle Alpi, lontane da quelle
// usate negli altri test.
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

/**
 * Verifica la risposta della ricerca del percorso, il passaggio dei sentieri chiusi al motore e la
 * traduzione degli errori del motore in risposte HTTP.
 */
class TrailRouteIT extends AbstractIntegrationTest {

    /** Motore di routing finto, che sostituisce il bean reale nel contesto Spring. */
    @MockitoBean
    private RoutingEngine routingEngine;

    /** Id del primo sentiero, da ovest a est. */
    private UUID first;

    /** Id del secondo sentiero, che parte dalla fine del primo e va verso nord. */
    private UUID second;

    /**
     * Tracciato restituito dal motore finto: segue i due sentieri con uno scarto di pochi metri, come succede
     * tra i dati di OpenStreetMap usati dal motore e le geometrie salvate nel database. La lunghezza è 4100 m.
     */
    private final EngineRoute alongBoth = new EngineRoute(List.of(
            List.of(10.000, 45.00005), List.of(10.005, 45.00005), List.of(10.010, 45.00005),
            List.of(10.01005, 45.015), List.of(10.01005, 45.030)), 4_100);

    /** Inserisce i due sentieri consecutivi prima di ogni test. */
    @BeforeEach
    void insertTrails() {
        first = insertTrail("Primo tratto", "LINESTRING(10.000 45.000, 10.005 45.000, 10.010 45.000)");
        second = insertTrail("Secondo tratto", "LINESTRING(10.010 45.000, 10.010 45.015, 10.010 45.030)");
    }

    /**
     * Un ospite chiede il percorso: la risposta 200 deve contenere il tracciato del motore, la sua lunghezza e
     * gli id dei sentieri attraversati nell'ordine di percorrenza. Partenza e arrivo devono arrivare al motore
     * invariati, in ordine longitudine e latitudine.
     */
    @Test
    void guestGetsTheEngineRouteWithTheTrailsItFollowsInOrder() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.of(alongBoth));

        HttpResult result = route(10.000, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(200);
        // La richiesta rivela la posizione dell'utente: a differenza del resto del catalogo la risposta non
        // deve essere messa in cache né avere un ETag.
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
        verify(routingEngine).route(eq(10.000), eq(45.000), eq(10.010), eq(45.030), any());
    }

    /**
     * Dopo che il gestore chiude il secondo sentiero, la sua geometria deve arrivare al motore come area da
     * evitare (un MultiPolygon in GeoJSON) e il sentiero non deve comparire tra quelli del percorso.
     */
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
        // L'unione delle aree può comprendere anche sentieri chiusi da altri test: basta che contenga le
        // coordinate di quello chiuso qui (longitudine 10.01).
        assertThat(avoid.getValue()).contains("[10.01");
    }

    /** Se il motore non trova un percorso, la risposta deve essere 422 con slug {@code no-route}. */
    @Test
    void noRouteFromTheEngineIsA422NoRoute() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());

        HttpResult result = route(10.000, 45.000, 11.000, 46.000);

        assertThat(result.status()).isEqualTo(422);
        assertThat(isProblemJson(result)).isTrue();
        assertThat(json(result.body()).get("type").asString()).endsWith("/no-route");
    }

    /**
     * Il percorso deve stare sui sentieri del parco: se la partenza è a più di un chilometro dai sentieri si
     * attende 422 {@code no-route}, anche se il motore restituirebbe comunque un tracciato.
     */
    @Test
    void startMoreThanOneKilometerFromTheTrailsIsA422NoRoute() {
        // Il motore aggancerebbe la partenza al sentiero più vicino, che qui è a circa 4 km.
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(Optional.of(alongBoth));

        HttpResult result = route(9.950, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(422);
        assertThat(json(result.body()).get("type").asString()).endsWith("/no-route");
    }

    /**
     * Se il motore non è raggiungibile, la risposta deve essere 503 con slug {@code routing-unavailable} e
     * l'header Retry-After a 5 secondi, così l'app sa quando riprovare.
     */
    @Test
    void engineDownIsA503WithRetryAfter() {
        when(routingEngine.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenThrow(new RoutingUnavailableException("down", null));

        HttpResult result = route(10.000, 45.000, 10.010, 45.030);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.headers().getFirst("Retry-After")).isEqualTo("5");
        assertThat(json(result.body()).get("type").asString()).endsWith("/routing-unavailable");
    }

    /**
     * Chiede il percorso tra due punti senza autenticazione.
     *
     * @param fromLon la longitudine di partenza
     * @param fromLat la latitudine di partenza
     * @param toLon   la longitudine di arrivo
     * @param toLat   la latitudine di arrivo
     * @return la risposta del server
     */
    private HttpResult route(double fromLon, double fromLat, double toLon, double toLat) {
        return call(HttpMethod.GET, "/api/v1/trails/route?fromLon=" + fromLon + "&fromLat=" + fromLat
                + "&toLon=" + toLon + "&toLat=" + toLat, null, null);
    }
}
