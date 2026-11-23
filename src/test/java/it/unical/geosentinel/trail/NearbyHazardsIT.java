// Test di integrazione delle liste di pericoli per posizione e per sentiero, con chiamate HTTP vere e PostGIS
// in Testcontainers. La rotta GET /hazards/nearby ("Pericoli vicino a me" nell'app) restituisce solo le
// segnalazioni verificate, di qualsiasi utente, dalla più vicina; la rotta /hazards/trail/{id} mostra le
// segnalazioni di un sentiero con regole di visibilità diverse a seconda di chi chiede.
package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica filtri, ordinamento, limiti e permessi delle liste di pericoli vicini e per sentiero.
 */
class NearbyHazardsIT extends AbstractIntegrationTest {

    /** Latitudine del centro della ricerca. Spostarsi di 0,001 gradi di latitudine vale circa 111 metri. */
    private static final double LAT = 45.0;

    /** Longitudine del centro della ricerca e di tutte le segnalazioni create. */
    private static final double LON = 7.0;

    /** Rotta della ricerca centrata in ({@link #LAT}, {@link #LON}) con raggio e limite di default. */
    private static final String NEARBY = "/api/v1/hazards/nearby?lat=" + LAT + "&lon=" + LON;

    /**
     * Due segnalazioni verificate di utenti diversi, una a circa 1,1 km e una a circa 111 m. Devono arrivare
     * entrambe, dalla più vicina, con stato {@code VERIFIED}, coordinate nel giusto ordine e senza l'id di chi
     * ha segnalato.
     */
    @Test
    void returnsVerifiedHazardsOfEveryoneNearestFirst() {
        String hiker = obtainToken(Role.HIKER);
        String other = obtainToken(Role.HIKER);
        String far = createVerified(other, LAT + 0.01);
        String near = createVerified(hiker, LAT + 0.001);

        HttpResult result = call(HttpMethod.GET, NEARBY, hiker, null);

        assertThat(result.status()).isEqualTo(200);
        JsonNode body = json(result.body());
        assertThat(ids(body)).containsExactly(near, far);
        JsonNode first = body.get(0);
        assertThat(first.get("status").asString()).isEqualTo("VERIFIED");
        // Latitudine e longitudine non devono risultare scambiate.
        assertThat(first.get("location").get("lat").asDouble()).isEqualTo(LAT + 0.001);
        assertThat(first.get("location").get("lon").asDouble()).isEqualTo(LON);
        assertThat(first.has("reporterId")).isFalse();
    }

    /**
     * Le segnalazioni ancora da verificare ({@code SUBMITTED}), rifiutate o risolte non sono pericoli attivi:
     * nella lista deve comparire solo quella verificata.
     */
    @Test
    void excludesHazardsThatAreNotActive() {
        String hiker = obtainToken(Role.HIKER);
        String verified = createVerified(hiker, LAT + 0.001);
        createHazard(hiker, LAT + 0.002);
        changeStatus(createHazard(hiker, LAT + 0.003), "REJECTED");
        String resolved = createVerified(hiker, LAT + 0.004);
        changeStatus(resolved, "RESOLVED");

        JsonNode body = json(call(HttpMethod.GET, NEARBY, hiker, null).body());

        assertThat(ids(body)).containsExactly(verified);
    }

    /**
     * Con il raggio di default (5 km) una segnalazione a circa 11 km resta fuori. Un raggio di 150 metri lascia
     * solo la più vicina, e lo stesso fa un limite di un risultato.
     */
    @Test
    void respectsRadiusAndLimit() {
        String hiker = obtainToken(Role.HIKER);
        String inside = createVerified(hiker, LAT + 0.001);
        String alsoInside = createVerified(hiker, LAT + 0.002);
        createVerified(hiker, LAT + 0.1);

        assertThat(ids(json(call(HttpMethod.GET, NEARBY, hiker, null).body()))).containsExactly(inside, alsoInside);
        assertThat(ids(json(call(HttpMethod.GET, NEARBY + "&radius=150", hiker, null).body()))).containsExactly(inside);
        assertThat(ids(json(call(HttpMethod.GET, NEARBY + "&limit=1", hiker, null).body()))).containsExactly(inside);
    }

    /** Senza token la rotta deve rispondere 401 con un corpo ProblemDetail. */
    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, NEARBY, null, null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(isProblemJson(result)).isTrue();
    }

    /**
     * Parametri fuori dall'intervallo ammesso devono dare 400: latitudine oltre 90, raggio oltre 50 km, limite
     * oltre 50 risultati, latitudine mancante.
     */
    @Test
    void outOfRangeParametersAreABadRequest() {
        String hiker = obtainToken(Role.HIKER);

        assertThat(call(HttpMethod.GET, "/api/v1/hazards/nearby?lat=91&lon=7", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, NEARBY + "&radius=50001", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, NEARBY + "&limit=51", hiker, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/api/v1/hazards/nearby?lon=7", hiker, null).status()).isEqualTo(400);
    }

    /**
     * Nella lista di un sentiero le segnalazioni verificate sono visibili a tutti, quelle ancora da verificare
     * solo a chi le ha create e ai gestori del parco. L'ordine è dalla più recente.
     */
    @Test
    void trailHazardsShowSubmittedOnlyToTheirReporterAndToParkManagers() {
        UUID trailId = insertTrail("Sentiero pericoli", "LINESTRING(%s %s, %s %s)".formatted(LON, LAT, LON, LAT + 0.01));
        String reporter = obtainToken(Role.HIKER);
        String verified = createVerified(reporter, LAT + 0.001);
        String submitted = createHazard(reporter, LAT + 0.002);
        String path = "/api/v1/hazards/trail/" + trailId;

        assertThat(ids(json(call(HttpMethod.GET, path, obtainToken(Role.HIKER), null).body()))).containsExactly(verified);
        assertThat(ids(json(call(HttpMethod.GET, path, reporter, null).body()))).containsExactly(submitted, verified);
        assertThat(ids(json(call(HttpMethod.GET, path, obtainToken(Role.PARK_MANAGER), null).body())))
                .containsExactly(submitted, verified);
    }

    /** Con 60 segnalazioni verificate sullo stesso sentiero, la lista ne restituisce al massimo 50. */
    @Test
    void trailHazardsAreLimited() {
        UUID trailId = insertTrail("Sentiero affollato", "LINESTRING(%s %s, %s %s)".formatted(LON, LAT, LON, LAT + 0.01));
        UUID reporter = createUser(Role.HIKER).getId();
        jdbcTemplate.update("""
                INSERT INTO hazard_reports (id, reporter_id, type, severity, status, location, created_at)
                SELECT gen_random_uuid(), ?, 'FALLEN_TREE', 'LOW', 'VERIFIED', ST_SetSRID(ST_MakePoint(?, ?), 4326), now()
                FROM generate_series(1, 60)
                """, reporter, LON, LAT + 0.005);

        assertThat(json(call(HttpMethod.GET, "/api/v1/hazards/trail/" + trailId, obtainToken(Role.HIKER), null).body()))
                .hasSize(50);
    }

    /**
     * Crea una segnalazione e la fa verificare da un gestore.
     *
     * @param token l'access token di chi segnala
     * @param lat   la latitudine della segnalazione (la longitudine è sempre {@link #LON})
     * @return l'id della segnalazione
     */
    private String createVerified(String token, double lat) {
        String id = createHazard(token, lat);
        changeStatus(id, "VERIFIED");
        return id;
    }

    /**
     * Crea una segnalazione di albero caduto, che resta nello stato {@code SUBMITTED}.
     *
     * @param token l'access token di chi segnala
     * @param lat   la latitudine della segnalazione
     * @return l'id della segnalazione
     */
    private String createHazard(String token, double lat) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"FALLEN_TREE","severity":"HIGH","location":{"lat":%s,"lon":%s}}
                """.formatted(lat, LON));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    /**
     * Cambia lo stato di una segnalazione con un gestore del parco appena creato.
     *
     * @param id     l'id della segnalazione
     * @param status il nuovo stato
     */
    private void changeStatus(String id, String status) {
        HttpResult result = call(HttpMethod.PATCH, "/api/v1/hazards/" + id + "/status", obtainToken(Role.PARK_MANAGER),
                """
                {"status":"%s"}
                """.formatted(status));
        assertThat(result.status()).isEqualTo(200);
    }

    /**
     * Estrae gli id da una lista JSON di segnalazioni.
     *
     * @param list l'array JSON
     * @return gli id nell'ordine della lista
     */
    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.forEach(h -> ids.add(h.get("id").asString()));
        return ids;
    }
}
