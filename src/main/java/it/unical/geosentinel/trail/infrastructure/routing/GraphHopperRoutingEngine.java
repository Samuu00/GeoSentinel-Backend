package it.unical.geosentinel.trail.infrastructure.routing;

import it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException;
import it.unical.geosentinel.trail.application.RoutingEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RoutingEngine} su GraphHopper ({@code POST /route}, ADR-0028). Le aree vietate vanno nel custom model della
 * richiesta: con il profilo senza CH GraphHopper lo accetta, e una priorità 0 esclude gli archi dentro l'area.
 * Un 400 di GraphHopper ("Cannot find point", "Connection between locations not found") è "nessun percorso";
 * rete, timeout e 5xx sono il motore che non risponde. Mai coordinate nei log né nei messaggi.
 */
@Component
@EnableConfigurationProperties(RoutingProperties.class)
public class GraphHopperRoutingEngine implements RoutingEngine {

    private static final String AREA_ID = "closed";

    private final RestClient client;
    private final JsonMapper jsonMapper;
    private final String profile;

    @Autowired
    // RestClient.builder() di Spring Web: il Builder come bean richiederebbe il modulo spring-boot-restclient
    public GraphHopperRoutingEngine(JsonMapper jsonMapper, RoutingProperties properties) {
        this(RestClient.builder().baseUrl(properties.url()).requestFactory(requestFactory(properties)).build(), jsonMapper, properties.profile());
    }

    // Nei test il client arriva da MockRestServiceServer, che ha la sua request factory
    GraphHopperRoutingEngine(RestClient client, JsonMapper jsonMapper, String profile) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.profile = profile;
    }

    private static JdkClientHttpRequestFactory requestFactory(RoutingProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public Optional<EngineRoute> route(double fromLon, double fromLat, double toLon, double toLat, String avoidGeoJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        // GraphHopper vuole [lon, lat] come GeoJSON
        body.put("points", List.of(List.of(fromLon, fromLat), List.of(toLon, toLat)));
        body.put("profile", profile);
        body.put("points_encoded", false);
        body.put("instructions", false);
        if (avoidGeoJson != null) {
            Map<String, Object> feature = Map.of("type", "Feature", "id", AREA_ID, "properties", Map.of(),
                    "geometry", jsonMapper.readTree(avoidGeoJson));
            body.put("custom_model", Map.of(
                    "priority", List.of(Map.of("if", "in_" + AREA_ID, "multiply_by", "0")),
                    "areas", Map.of("type", "FeatureCollection", "features", List.of(feature))));
        }
        JsonNode response;
        try {
            response = client.post().uri("/route").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, reply) -> {
                        throw new RoutingUnavailableException("Routing engine answered " + reply.getStatusCode().value(), null);
                    })
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new RoutingUnavailableException("Routing engine unreachable", e);
        }
        JsonNode path = response == null ? null : response.path("paths").path(0);
        if (path == null || path.isMissingNode()) return Optional.empty();
        List<List<Double>> coordinates = new ArrayList<>();
        for (JsonNode pair : path.path("points").path("coordinates")) {
            coordinates.add(List.of(pair.get(0).asDouble(), pair.get(1).asDouble()));
        }
        if (coordinates.size() < 2) return Optional.empty();
        return Optional.of(new EngineRoute(coordinates, path.path("distance").asDouble()));
    }
}
