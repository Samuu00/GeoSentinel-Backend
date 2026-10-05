package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// "Le mie segnalazioni" (M4.3 dell'app): GET /hazards/mine, paginata, solo quelle dell'utente, dalla più recente
class MyHazardsIT extends AbstractIntegrationTest {

    @Test
    void returnsOnlyOwnReportsNewestFirstWithCreatedAt() {
        String hiker = obtainToken(Role.HIKER);
        String other = obtainToken(Role.HIKER);
        String first = createHazard(hiker, "FALLEN_TREE");
        String second = createHazard(hiker, "LANDSLIDE");
        String foreign = createHazard(other, "FLOODING");

        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine", hiker, null);

        assertThat(result.status()).isEqualTo(200);
        JsonNode body = json(result.body());
        List<String> ids = ids(body);
        assertThat(ids).containsExactly(second, first).doesNotContain(foreign);
        assertThat(body.get("page").get("totalElements").asLong()).isEqualTo(2);
        JsonNode newest = body.get("content").get(0);
        assertThat(newest.get("type").asString()).isEqualTo("LANDSLIDE");
        assertThat(newest.get("status").asString()).isEqualTo("SUBMITTED");
        // createdAt c'è ed è più recente di quello della prima segnalazione
        Instant newestAt = Instant.parse(newest.get("createdAt").asString());
        Instant oldestAt = Instant.parse(body.get("content").get(1).get("createdAt").asString());
        assertThat(newestAt).isAfterOrEqualTo(oldestAt);
        // nessun dato su chi ha segnalato
        assertThat(newest.has("reporterId")).isFalse();
    }

    @Test
    void anotherUserDoesNotSeeMyReports() {
        String hiker = obtainToken(Role.HIKER);
        createHazard(hiker, "FALLEN_TREE");

        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(200);
        assertThat(json(result.body()).get("content").size()).isZero();
    }

    @Test
    void pagesDoNotOverlap() {
        String hiker = obtainToken(Role.HIKER);
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            created.add(createHazard(hiker, "OTHER"));
        }

        JsonNode page0 = json(call(HttpMethod.GET, "/api/v1/hazards/mine?page=0&size=2", hiker, null).body());
        JsonNode page1 = json(call(HttpMethod.GET, "/api/v1/hazards/mine?page=1&size=2", hiker, null).body());

        assertThat(page0.get("page").get("totalPages").asInt()).isEqualTo(2);
        List<String> all = new ArrayList<>(ids(page0));
        all.addAll(ids(page1));
        assertThat(all).hasSize(3).doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).isEqualTo(created);
    }

    @Test
    void rejectedReportsStayVisibleToTheirAuthor() {
        String hiker = obtainToken(Role.HIKER);
        String id = createHazard(hiker, "WILDLIFE");
        HttpResult rejected = call(HttpMethod.PATCH, "/api/v1/hazards/" + id + "/status", obtainToken(Role.PARK_MANAGER),
                """
                {"status":"REJECTED"}
                """);
        assertThat(rejected.status()).isEqualTo(200);

        JsonNode mine = json(call(HttpMethod.GET, "/api/v1/hazards/mine", hiker, null).body());

        assertThat(mine.get("content").get(0).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine", null, null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(isProblemJson(result)).isTrue();
    }

    @Test
    void sortingByAnUnknownFieldIsABadRequest() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine?sort=reporterId,asc", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    void createdHazardAlreadyCarriesCreatedAt() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", obtainToken(Role.HIKER), hazardJson("SNOW_ICE"));

        assertThat(result.status()).isEqualTo(201);
        assertThat(json(result.body()).get("createdAt").asString()).isNotBlank();
    }

    private String createHazard(String token, String type) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, hazardJson(type));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    private static String hazardJson(String type) {
        return """
                {"type":"%s","severity":"MEDIUM","location":{"lat":39.30,"lon":16.40}}
                """.formatted(type);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }
}
