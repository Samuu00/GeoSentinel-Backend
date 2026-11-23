// Test di integrazione della lista "Le mie segnalazioni", con chiamate HTTP vere e PostGIS in Testcontainers.
// La rotta GET /hazards/mine restituisce, paginate e dalla più recente, solo le segnalazioni di pericolo
// create dall'utente autenticato, comprese quelle che il gestore ha rifiutato.
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

/**
 * Verifica contenuto, ordinamento, paginazione e permessi della lista delle proprie segnalazioni.
 */
class MyHazardsIT extends AbstractIntegrationTest {

    /**
     * Un escursionista crea due segnalazioni e un altro utente una. La lista del primo deve contenere solo le
     * sue due, dalla più recente, con tipo, stato {@code SUBMITTED} e data di creazione, e senza l'id di chi ha
     * segnalato.
     */
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
        // La data di creazione è presente e non è precedente a quella della prima segnalazione.
        Instant newestAt = Instant.parse(newest.get("createdAt").asString());
        Instant oldestAt = Instant.parse(body.get("content").get(1).get("createdAt").asString());
        assertThat(newestAt).isAfterOrEqualTo(oldestAt);
        // La risposta non espone l'identità di chi ha segnalato.
        assertThat(newest.has("reporterId")).isFalse();
    }

    /** Un altro utente non deve vedere nella propria lista le segnalazioni altrui: la sua pagina è vuota. */
    @Test
    void anotherUserDoesNotSeeMyReports() {
        String hiker = obtainToken(Role.HIKER);
        createHazard(hiker, "FALLEN_TREE");

        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(200);
        assertThat(json(result.body()).get("content").size()).isZero();
    }

    /**
     * Con tre segnalazioni e pagine da due, le due pagine devono contenere insieme tutte e tre le segnalazioni,
     * senza ripetizioni.
     */
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

    /** Una segnalazione rifiutata dal gestore deve restare visibile al suo autore, con lo stato {@code REJECTED}. */
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

    /** Senza token la rotta deve rispondere 401 con un corpo ProblemDetail. */
    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine", null, null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(isProblemJson(result)).isTrue();
    }

    /** Un ordinamento su un campo non ammesso deve dare 400. */
    @Test
    void sortingByAnUnknownFieldIsABadRequest() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/mine?sort=reporterId,asc", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(400);
    }

    /** Anche la risposta alla creazione di una segnalazione deve contenere già la data di creazione. */
    @Test
    void createdHazardAlreadyCarriesCreatedAt() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", obtainToken(Role.HIKER), hazardJson("SNOW_ICE"));

        assertThat(result.status()).isEqualTo(201);
        assertThat(json(result.body()).get("createdAt").asString()).isNotBlank();
    }

    /**
     * Crea una segnalazione del tipo indicato e controlla che riesca.
     *
     * @param token l'access token di chi segnala
     * @param type  il tipo di pericolo, ad esempio {@code LANDSLIDE}
     * @return l'id della segnalazione creata
     */
    private String createHazard(String token, String type) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, hazardJson(type));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    /**
     * Costruisce il corpo di una segnalazione di gravità media in un punto fisso.
     *
     * @param type il tipo di pericolo
     * @return il corpo JSON
     */
    private static String hazardJson(String type) {
        return """
                {"type":"%s","severity":"MEDIUM","location":{"lat":39.30,"lon":16.40}}
                """.formatted(type);
    }

    /**
     * Estrae gli id delle segnalazioni da una pagina di risultati.
     *
     * @param page il corpo JSON della pagina, con il campo {@code content}
     * @return gli id nell'ordine della pagina
     */
    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }
}
