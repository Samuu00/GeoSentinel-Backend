// Test di integrazione del controllo di concorrenza ottimistico sui ticket di manutenzione, con chiamate HTTP
// vere e PostGIS e Redis in Testcontainers. Per evitare che una modifica sovrascriva in silenzio quella di un
// altro utente (lost update), il client manda nell'header If-Match la versione che ha letto: il servizio la
// confronta con quella attuale e, al commit, il campo @Version di JPA fa da ultima difesa.
package it.unical.geosentinel.maintenance;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il rifiuto degli aggiornamenti basati su una versione vecchia, la gestione di aggiornamenti
 * paralleli e lo svuotamento della cache dei ticket dopo una modifica.
 */
class MaintenanceOptimisticLockIT extends AbstractIntegrationTest {

    /** Access token del gestore del parco che crea e modifica il ticket. */
    private String manager;

    /** Id del sentiero a cui è legato il ticket. */
    private UUID trailId;

    /** Id del ticket creato prima di ogni test. */
    private UUID ticketId;

    /**
     * Crea un sentiero e un ticket ad alta priorità su di esso, e controlla che il ticket appena creato abbia
     * ETag {@code "0"}, cioè versione 0.
     */
    @BeforeEach
    void createTicket() {
        manager = obtainToken(Role.PARK_MANAGER);
        trailId = insertTrail("Sentiero con frana", "LINESTRING(16.48 39.30, 16.50 39.30)");
        HttpResult created = call(HttpMethod.POST, "/api/v1/maintenance-tickets", manager, """
                {"title":"Frana km 3","priority":"HIGH","trailId":"%s"}
                """.formatted(trailId));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.headers().getETag()).isEqualTo("\"0\"");
        ticketId = UUID.fromString(json(created.body()).get("id").asString());
    }

    /**
     * Due aggiornamenti in sequenza con la stessa versione 0: il primo riesce (200), il secondo deve essere
     * rifiutato con 409 e slug {@code concurrent-modification}. Il ticket deve restare alla versione 1 con il
     * titolo del primo aggiornamento.
     */
    @Test
    void secondUpdateWithTheSameVersionIsRejected() {
        assertThat(update("\"0\"", "Primo").status()).isEqualTo(200);

        HttpResult stale = update("\"0\"", "Secondo");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(json(stale.body()).get("type").asString()).endsWith("/concurrent-modification");
        assertThat(currentVersionAndTitle()).isEqualTo(List.of("\"1\"", "Primo"));
    }

    /**
     * Due aggiornamenti in parallelo con la stessa versione: esattamente uno vince (200) e l'altro riceve 409,
     * sia che venga fermato dal confronto esplicito con If-Match sia dal controllo di {@code @Version} al
     * commit. Alla fine la versione è 1.
     */
    @RepeatedTest(5)
    void concurrentUpdatesWithTheSameVersionLetOnlyOneWin() throws Exception {
        List<Integer> statuses = runConcurrently(2, i -> update("\"0\"", "Titolo " + i).status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(currentVersionAndTitle().get(0)).isEqualTo("\"1\"");
    }

    /**
     * La lista dei ticket di un sentiero è in cache: dopo aver aggiornato il titolo di un ticket, una nuova
     * lettura della lista deve mostrare il titolo nuovo e non quello rimasto in cache.
     */
    @Test
    void cachedTicketsOfTheTrailAreEvictedAfterAnUpdate() {
        String path = "/api/v1/maintenance-tickets/trail/" + trailId;
        assertThat(json(call(HttpMethod.GET, path, manager, null).body()).get(0).get("title").asString())
                .isEqualTo("Frana km 3");

        update("\"0\"", "Frana messa in sicurezza");

        assertThat(json(call(HttpMethod.GET, path, manager, null).body()).get(0).get("title").asString())
                .isEqualTo("Frana messa in sicurezza");
    }

    /**
     * Aggiorna il titolo del ticket mandando la versione indicata nell'header If-Match.
     *
     * @param ifMatch il valore dell'header, ad esempio {@code "0"} tra virgolette
     * @param title   il nuovo titolo
     * @return la risposta del server
     */
    private HttpResult update(String ifMatch, String title) {
        return call(HttpMethod.PUT, "/api/v1/maintenance-tickets/" + ticketId, manager,
                "{\"title\":\"%s\",\"priority\":\"HIGH\"}".formatted(title), Map.of("If-Match", ifMatch));
    }

    /**
     * Rilegge il ticket dal server.
     *
     * @return una lista di due elementi: l'ETag (la versione tra virgolette) e il titolo attuale
     */
    private List<String> currentVersionAndTitle() {
        HttpResult current = call(HttpMethod.GET, "/api/v1/maintenance-tickets/" + ticketId, manager, null);
        return List.of(current.headers().getETag(), json(current.body()).get("title").asString());
    }
}
