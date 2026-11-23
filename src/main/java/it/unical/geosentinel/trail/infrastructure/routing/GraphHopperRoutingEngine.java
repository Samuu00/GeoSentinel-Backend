// Adattatore che implementa la porta RoutingEngine chiamando il servizio GraphHopper via HTTP
// (POST /route). Traduce le risposte di GraphHopper nel risultato del dominio e i suoi errori nelle
// eccezioni dell'applicazione.
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
 * Implementazione di {@link RoutingEngine} su GraphHopper. Ogni richiesta porta un custom model (ammesso
 * dal profilo senza Contraction Hierarchies) che tiene solo i tratti degli itinerari escursionistici
 * ({@code foot_network}) e, con priorità 0, esclude gli archi dentro le aree vietate.
 * Un 400 di GraphHopper ("Cannot find point", "Connection between locations not found") significa che
 * non esiste un percorso; errori di rete, timeout e 5xx indicano invece che il motore non risponde.
 * Le coordinate non compaiono mai nei log né nei messaggi delle eccezioni.
 */
@Component
@EnableConfigurationProperties(RoutingProperties.class)
public class GraphHopperRoutingEngine implements RoutingEngine {

    /** Identificativo della feature GeoJSON delle aree vietate, richiamato nel custom model come {@code in_closed}. */
    private static final String AREA_ID = "closed";

    private final RestClient client;
    private final JsonMapper jsonMapper;
    /** Profilo di GraphHopper usato per il calcolo. */
    private final String profile;

    /**
     * Costruttore usato da Spring. Il client si crea con {@code RestClient.builder()} di Spring Web: il
     * Builder come bean richiederebbe il modulo aggiuntivo spring-boot-restclient.
     *
     * @param jsonMapper mapper JSON, per leggere il GeoJSON delle aree vietate
     * @param properties indirizzo, profilo e timeout
     */
    @Autowired
    public GraphHopperRoutingEngine(JsonMapper jsonMapper, RoutingProperties properties) {
        this(RestClient.builder().baseUrl(properties.url()).requestFactory(requestFactory(properties)).build(), jsonMapper, properties.profile());
    }

    /**
     * Costruttore visibile nel package, usato dai test: il client arriva da MockRestServiceServer, che ha
     * la sua request factory.
     *
     * @param client     client HTTP già configurato
     * @param jsonMapper mapper JSON
     * @param profile    profilo di GraphHopper
     */
    GraphHopperRoutingEngine(RestClient client, JsonMapper jsonMapper, String profile) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.profile = profile;
    }

    /**
     * Crea la factory delle richieste basata sull'HttpClient del JDK, con i timeout di connessione e di
     * lettura presi dalla configurazione.
     *
     * @param properties configurazione del motore
     * @return la factory
     */
    private static JdkClientHttpRequestFactory requestFactory(RoutingProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /**
     * Costruisce il body della richiesta, chiama GraphHopper e legge il primo percorso della risposta.
     *
     * @return il percorso, oppure vuoto se GraphHopper risponde 400 o restituisce meno di due punti
     * @throws RoutingUnavailableException se il servizio non è raggiungibile o risponde con un 5xx
     */
    @Override
    public Optional<EngineRoute> route(double fromLon, double fromLat, double toLon, double toLat, String avoidGeoJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        // GraphHopper vuole i punti come coppie [lon, lat], come GeoJSON.
        body.put("points", List.of(List.of(fromLon, fromLat), List.of(toLon, toLat)));
        body.put("profile", profile);
        body.put("points_encoded", false);
        body.put("instructions", false);
        // Solo i sentieri: i tratti che non fanno parte di un itinerario escursionistico di OpenStreetMap
        // (route=hiking, la stessa fonte da cui sono importati i sentieri del parco) ricevono priorità 0,
        // quindi il percorso non usa strade né scorciatoie.
        List<Map<String, Object>> priority = new ArrayList<>();
        priority.add(Map.of("if", "foot_network == MISSING", "multiply_by", "0"));
        Map<String, Object> customModel = new LinkedHashMap<>();
        customModel.put("priority", priority);
        if (avoidGeoJson != null) {
            Map<String, Object> feature = Map.of("type", "Feature", "id", AREA_ID, "properties", Map.of(),
                    "geometry", jsonMapper.readTree(avoidGeoJson));
            priority.add(Map.of("if", "in_" + AREA_ID, "multiply_by", "0"));
            customModel.put("areas", Map.of("type", "FeatureCollection", "features", List.of(feature)));
        }
        body.put("custom_model", customModel);
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
