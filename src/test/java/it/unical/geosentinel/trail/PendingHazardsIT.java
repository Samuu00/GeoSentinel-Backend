package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Coda "Da verificare" del gestore (M8.1 dell'app): GET /hazards/pending, le SUBMITTED di tutti, ovunque siano
class PendingHazardsIT extends AbstractIntegrationTest {

    @Test
    void listsSubmittedReportsOfEveryoneEvenFarFromTrails() {
        // in mezzo al mare: nessun sentiero entro 100 m, quindi /hazards/trail/{id} non la vedrebbe mai
        String far = createHazard(obtainToken(Role.HIKER), 38.0, 15.0);
        String other = createHazard(obtainToken(Role.HIKER), 39.30, 16.40);

        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending?size=100", obtainToken(Role.PARK_MANAGER), null);

        assertThat(result.status()).isEqualTo(200);
        List<String> ids = ids(json(result.body()));
        assertThat(ids).contains(far, other);
        // dalla più recente
        assertThat(ids.indexOf(other)).isLessThan(ids.indexOf(far));
    }

    @Test
    void verifiedAndRejectedReportsLeaveTheQueue() {
        String hiker = obtainToken(Role.HIKER);
        String manager = obtainToken(Role.PARK_MANAGER);
        String verified = createHazard(hiker, 39.30, 16.40);
        String rejected = createHazard(hiker, 39.30, 16.40);
        String pending = createHazard(hiker, 39.30, 16.40);
        changeStatus(manager, verified, "VERIFIED");
        changeStatus(manager, rejected, "REJECTED");

        List<String> ids = ids(json(call(HttpMethod.GET, "/api/v1/hazards/pending?size=100", manager, null).body()));

        assertThat(ids).contains(pending).doesNotContain(verified, rejected);
    }

    @Test
    void adminSeesTheQueueToo() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", obtainToken(Role.ADMIN), null);

        assertThat(result.status()).isEqualTo(200);
    }

    @Test
    void hikerIsForbidden() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(403);
        assertThat(isProblemJson(result)).isTrue();
    }

    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", null, null);

        assertThat(result.status()).isEqualTo(401);
    }

    @Test
    void sortingByAnUnknownFieldIsABadRequest() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending?sort=reporterId,asc", obtainToken(Role.PARK_MANAGER), null);

        assertThat(result.status()).isEqualTo(400);
    }

    private String createHazard(String token, double lat, double lon) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"OTHER","severity":"MEDIUM","location":{"lat":%s,"lon":%s}}
                """.formatted(lat, lon));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    private void changeStatus(String token, String id, String status) {
        HttpResult result = call(HttpMethod.PATCH, "/api/v1/hazards/" + id + "/status", token, """
                {"status":"%s"}
                """.formatted(status));
        assertThat(result.status()).isEqualTo(200);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }
}
