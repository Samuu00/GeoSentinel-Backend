package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// "Pericoli vicino a me" (M4.4 dell'app): GET /hazards/nearby, solo VERIFIED, di tutti, dal più vicino
class NearbyHazardsIT extends AbstractIntegrationTest {

    // centro della ricerca; 0,001° di latitudine sono circa 111 m
    private static final double LAT = 45.0;
    private static final double LON = 7.0;
    private static final String NEARBY = "/api/v1/hazards/nearby?lat=" + LAT + "&lon=" + LON;

    @Test
    void returnsVerifiedHazardsOfEveryoneNearestFirst() {
        String hiker = obtainToken(Role.HIKER);
        String other = obtainToken(Role.HIKER);
        String far = createVerified(other, LAT + 0.01);   // circa 1,1 km
        String near = createVerified(hiker, LAT + 0.001); // circa 111 m

        HttpResult result = call(HttpMethod.GET, NEARBY, hiker, null);

        assertThat(result.status()).isEqualTo(200);
        JsonNode body = json(result.body());
        assertThat(ids(body)).containsExactly(near, far);
        JsonNode first = body.get(0);
        assertThat(first.get("status").asString()).isEqualTo("VERIFIED");
        // coordinate non invertite
        assertThat(first.get("location").get("lat").asDouble()).isEqualTo(LAT + 0.001);
        assertThat(first.get("location").get("lon").asDouble()).isEqualTo(LON);
        assertThat(first.has("reporterId")).isFalse();
    }

    @Test
    void excludesHazardsThatAreNotActive() {
        String hiker = obtainToken(Role.HIKER);
        String verified = createVerified(hiker, LAT + 0.001);
        createHazard(hiker, LAT + 0.002); // SUBMITTED
        changeStatus(createHazard(hiker, LAT + 0.003), "REJECTED");
        String resolved = createVerified(hiker, LAT + 0.004);
        changeStatus(resolved, "RESOLVED");

        JsonNode body = json(call(HttpMethod.GET, NEARBY, hiker, null).body());

        assertThat(ids(body)).containsExactly(verified);
    }

    @Test
    void respectsRadiusAndLimit() {
        String hiker = obtainToken(Role.HIKER);
        String inside = createVerified(hiker, LAT + 0.001);
        String alsoInside = createVerified(hiker, LAT + 0.002);
        createVerified(hiker, LAT + 0.1); // circa 11 km, fuori dal raggio di default (5 km)

        assertThat(ids(json(call(HttpMethod.GET, NEARBY, hiker, null).body()))).containsExactly(inside, alsoInside);
        assertThat(ids(json(call(HttpMethod.GET, NEARBY + "&radius=150", hiker, null).body()))).containsExactly(inside);
        assertThat(ids(json(call(HttpMethod.GET, NEARBY + "&limit=1", hiker, null).body()))).containsExactly(inside);
    }

    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, NEARBY, null, null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(isProblemJson(result)).isTrue();
    }

    @Test
    void outOfRangeParametersAreABadRequest() {
        String hiker = obtainToken(Role.HIKER);

        assertThat(call(HttpMethod.GET, "/api/v1/hazards/nearby?lat=91&lon=7", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, NEARBY + "&radius=50001", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, NEARBY + "&limit=51", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/api/v1/hazards/nearby?lon=7", hiker, null).status()).isEqualTo(400);
    }

    private String createVerified(String token, double lat) {
        String id = createHazard(token, lat);
        changeStatus(id, "VERIFIED");
        return id;
    }

    private String createHazard(String token, double lat) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"FALLEN_TREE","severity":"HIGH","location":{"lat":%s,"lon":%s}}
                """.formatted(lat, LON));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    private void changeStatus(String id, String status) {
        HttpResult result = call(HttpMethod.PATCH, "/api/v1/hazards/" + id + "/status", obtainToken(Role.PARK_MANAGER),
                """
                {"status":"%s"}
                """.formatted(status));
        assertThat(result.status()).isEqualTo(200);
    }

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }
}
