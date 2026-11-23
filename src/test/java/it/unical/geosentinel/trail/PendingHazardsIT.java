// Test di integrazione della coda delle segnalazioni di pericolo da verificare, con chiamate HTTP vere e
// PostGIS in Testcontainers. La rotta GET /hazards/pending restituisce ai gestori del parco tutte le
// segnalazioni nello stato SUBMITTED, di qualsiasi utente e in qualsiasi posizione, anche lontane dai sentieri.
package it.unical.geosentinel.trail;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica contenuto, ordinamento e permessi della coda delle segnalazioni da verificare.
 */
class PendingHazardsIT extends AbstractIntegrationTest {

    /**
     * Due escursionisti creano una segnalazione ciascuno, una delle quali lontana da ogni sentiero. Il gestore
     * deve vederle entrambe nella coda, dalla più recente alla meno recente.
     */
    @Test
    void listsSubmittedReportsOfEveryoneEvenFarFromTrails() {
        // Un punto in mare, senza sentieri nel raggio di 100 metri: la lista delle segnalazioni di un sentiero
        // non lo mostrerebbe mai, la coda invece sì.
        String far = createHazard(obtainToken(Role.HIKER), 38.0, 15.0);
        String other = createHazard(obtainToken(Role.HIKER), 39.30, 16.40);

        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending?size=100", obtainToken(Role.PARK_MANAGER), null);

        assertThat(result.status()).isEqualTo(200);
        List<String> ids = ids(json(result.body()));
        assertThat(ids).contains(far, other);
        // La segnalazione creata per ultima viene per prima.
        assertThat(ids.indexOf(other)).isLessThan(ids.indexOf(far));
    }

    /** Una segnalazione verificata o rifiutata dal gestore deve uscire dalla coda; quella ancora in attesa resta. */
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

    /** Anche un amministratore deve poter leggere la coda (200). */
    @Test
    void adminSeesTheQueueToo() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", obtainToken(Role.ADMIN), null);

        assertThat(result.status()).isEqualTo(200);
    }

    /** Un escursionista non può leggere la coda: si attende 403 con un corpo ProblemDetail. */
    @Test
    void hikerIsForbidden() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", obtainToken(Role.HIKER), null);

        assertThat(result.status()).isEqualTo(403);
        assertThat(isProblemJson(result)).isTrue();
    }

    /** Senza token la rotta deve rispondere 401. */
    @Test
    void requiresLogin() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending", null, null);

        assertThat(result.status()).isEqualTo(401);
    }

    /** Un ordinamento su un campo non ammesso (qui l'autore della segnalazione) deve dare 400. */
    @Test
    void sortingByAnUnknownFieldIsABadRequest() {
        HttpResult result = call(HttpMethod.GET, "/api/v1/hazards/pending?sort=reporterId,asc", obtainToken(Role.PARK_MANAGER), null);

        assertThat(result.status()).isEqualTo(400);
    }

    /**
     * Crea una segnalazione generica di gravità media nel punto indicato.
     *
     * @param token l'access token di chi segnala
     * @param lat   la latitudine
     * @param lon   la longitudine
     * @return l'id della segnalazione creata
     */
    private String createHazard(String token, double lat, double lon) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"OTHER","severity":"MEDIUM","location":{"lat":%s,"lon":%s}}
                """.formatted(lat, lon));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body()).get("id").asString();
    }

    /**
     * Cambia lo stato di una segnalazione e controlla che l'operazione riesca.
     *
     * @param token  l'access token del gestore
     * @param id     l'id della segnalazione
     * @param status il nuovo stato, ad esempio {@code VERIFIED}
     */
    private void changeStatus(String token, String id, String status) {
        HttpResult result = call(HttpMethod.PATCH, "/api/v1/hazards/" + id + "/status", token, """
                {"status":"%s"}
                """.formatted(status));
        assertThat(result.status()).isEqualTo(200);
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
