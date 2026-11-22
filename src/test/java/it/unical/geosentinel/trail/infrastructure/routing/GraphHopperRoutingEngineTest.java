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

// La richiesta a GraphHopper (punti in [lon, lat], area vietata nel custom model) e come si leggono le risposte
class GraphHopperRoutingEngineTest {

    private MockRestServiceServer server;
    private GraphHopperRoutingEngine engine;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://graphhopper:8989");
        server = MockRestServiceServer.bindTo(builder).build();
        engine = new GraphHopperRoutingEngine(builder.build(), JsonMapper.builder().build(), "hike");
    }

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

    @Test
    void withoutAreasOnlyTheTrailsRuleIsSent() {
        server.expect(requestTo("http://graphhopper:8989/route"))
                .andExpect(jsonPath("$.custom_model.priority.length()").value(1))
                .andExpect(jsonPath("$.custom_model.priority[0].if").value("foot_network == MISSING"))
                .andExpect(jsonPath("$.custom_model.areas").doesNotExist())
                .andRespond(withSuccess("{\"paths\":[{\"distance\":1,\"points\":{\"coordinates\":[[1,2],[3,4]]}}]}", MediaType.APPLICATION_JSON));

        assertThat(engine.route(1, 2, 3, 4, null)).isPresent();
    }

    @Test
    void badRequestFromGraphHopperMeansNoRoute() {
        server.expect(requestTo("http://graphhopper:8989/route"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Connection between locations not found\"}"));

        assertThat(engine.route(1, 2, 3, 4, null)).isEmpty();
    }

    @Test
    void serverErrorMeansTheEngineIsUnavailable() {
        server.expect(requestTo("http://graphhopper:8989/route")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> engine.route(1, 2, 3, 4, null)).isInstanceOf(RoutingUnavailableException.class);
    }
}
