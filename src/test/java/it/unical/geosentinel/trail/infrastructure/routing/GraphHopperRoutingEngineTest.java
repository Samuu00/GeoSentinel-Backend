// Test unitario di GraphHopperRoutingEngine, l'adattatore che calcola il percorso tra due punti chiamando il
// motore di routing GraphHopper via HTTP. Il server GraphHopper è simulato con MockRestServiceServer, quindi
// il test controlla sia la richiesta inviata (punti in ordine [lon, lat], area vietata nel custom model) sia
// il modo in cui vengono interpretate le risposte, comprese quelle di errore.
package it.unical.geosentinel.trail.infrastructure.routing;

import it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException;
import it.unical.geosentinel.trail.application.RoutingEngine.EngineRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifica il corpo della richiesta {@code POST /route} costruito per GraphHopper e la traduzione delle
 * risposte (percorso trovato, nessun percorso, motore non disponibile).
 */
class GraphHopperRoutingEngineTest {

    /** Server HTTP simulato che riceve le richieste del client. */
    private MockRestServiceServer server;

    /** Adattatore sotto test, configurato con il profilo "hike". */
    private GraphHopperRoutingEngine engine;

    /** Collega il server simulato al RestClient e crea l'adattatore prima di ogni test. */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://graphhopper:8989");
        server = MockRestServiceServer.bindTo(builder).build();
        engine = new GraphHopperRoutingEngine(builder.build(), JsonMapper.builder().build(), "hike");
    }

    /**
     * Con un'area da evitare, la richiesta deve contenere i punti in ordine [lon, lat], il profilo, le
     * coordinate non codificate e un custom model con due regole di priorità: la prima azzera i tratti fuori
     * dagli itinerari escursionistici, la seconda azzera quelli dentro l'area vietata (un MultiPolygon con id
     * "closed"). Dalla risposta si devono leggere distanza e coordinate del percorso.
     */
    @Test
    void sendsLonLatPointsAndTheAreaToAvoidAndReadsThePath() {
        server.expect(requestTo("http://graphhopper:8989/route")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.points[0][0]").value(16.45))
                .andExpect(jsonPath("$.points[0][1]").value(39.33))
                .andExpect(jsonPath("$.profile").value("hike"))
                .andExpect(jsonPath("$.points_encoded").value(false))
                .andExpect(jsonPath("$.custom_model.priority[0].if").value("foot_network == MISSING"))
                .andExpect(jsonPath("$.custom_model.priority[1].if").value("in_closed"))
                .andExpect(jsonPath("$.custom_model.priority[1].multiply_by").value("0"))
                .andExpect(jsonPath("$.custom_model.areas.features[0].id").value("closed"))
                .andExpect(jsonPath("$.custom_model.areas.features[0].geometry.type").value("MultiPolygon"))
                .andRespond(withSuccess("""
                        {"paths":[{"distance":1234.5,"points":{"type":"LineString","coordinates":[[16.45,39.33],[16.46,39.32]]}}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<EngineRoute> route = engine.route(16.45, 39.33, 16.46, 39.32,
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[16.0,39.0],[16.1,39.0],[16.1,39.1],[16.0,39.0]]]]}");

        assertThat(route).isPresent();
        assertThat(route.get().distanceM()).isEqualTo(1234.5);
        assertThat(route.get().coordinates()).containsExactly(List.of(16.45, 39.33), List.of(16.46, 39.32));
        server.verify();
    }

    /**
     * Senza aree da evitare il custom model deve contenere solo la regola sugli itinerari escursionistici e
     * nessuna sezione {@code areas}.
     */
    @Test
    void withoutAreasOnlyTheTrailsRuleIsSent() {
        server.expect(requestTo("http://graphhopper:8989/route"))
                .andExpect(jsonPath("$.custom_model.priority.length()").value(1))
                .andExpect(jsonPath("$.custom_model.priority[0].if").value("foot_network == MISSING"))
                .andExpect(jsonPath("$.custom_model.areas").doesNotExist())
                .andRespond(withSuccess("{\"paths\":[{\"distance\":1,\"points\":{\"coordinates\":[[1,2],[3,4]]}}]}", MediaType.APPLICATION_JSON));

        assertThat(engine.route(1, 2, 3, 4, null)).isPresent();
    }

    /**
     * Un 400 di GraphHopper (ad esempio "Connection between locations not found") significa che tra i due punti
     * non esiste un percorso: il risultato atteso è un {@link Optional} vuoto, non un errore.
     */
    @Test
    void badRequestFromGraphHopperMeansNoRoute() {
        server.expect(requestTo("http://graphhopper:8989/route"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Connection between locations not found\"}"));

        assertThat(engine.route(1, 2, 3, 4, null)).isEmpty();
    }

    /**
     * Un errore del server GraphHopper (qui 503) significa che il motore non è disponibile: si attende una
     * {@link RoutingUnavailableException}.
     */
    @Test
    void serverErrorMeansTheEngineIsUnavailable() {
        server.expect(requestTo("http://graphhopper:8989/route")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> engine.route(1, 2, 3, 4, null)).isInstanceOf(RoutingUnavailableException.class);
    }
}
