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

// Lost update impedito: If-Match confrontato con la versione (expectedVersion) + @Version al commit
class MaintenanceOptimisticLockIT extends AbstractIntegrationTest {

    private String manager;
    private UUID trailId;
    private UUID ticketId;

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

    @Test
    void secondUpdateWithTheSameVersionIsRejected() {
        assertThat(update("\"0\"", "Primo").status()).isEqualTo(200);

        HttpResult stale = update("\"0\"", "Secondo");

        assertThat(stale.status()).isEqualTo(409);
        assertThat(json(stale.body()).get("type").asString()).endsWith("/concurrent-modification");
        assertThat(currentVersionAndTitle()).isEqualTo(List.of("\"1\"", "Primo"));
    }

    // Stessa versione, in parallelo: uno dei due perde (dal confronto esplicito o da @Version al commit)
    @RepeatedTest(5)
    void concurrentUpdatesWithTheSameVersionLetOnlyOneWin() throws Exception {
        List<Integer> statuses = runConcurrently(2, i -> update("\"0\"", "Titolo " + i).status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(currentVersionAndTitle().get(0)).isEqualTo("\"1\"");
    }

    @Test
    void cachedTicketsOfTheTrailAreEvictedAfterAnUpdate() {
        String path = "/api/v1/maintenance-tickets/trail/" + trailId;
        assertThat(json(call(HttpMethod.GET, path, manager, null).body()).get(0).get("title").asString())
                .isEqualTo("Frana km 3");

        update("\"0\"", "Frana messa in sicurezza");

        assertThat(json(call(HttpMethod.GET, path, manager, null).body()).get(0).get("title").asString())
                .isEqualTo("Frana messa in sicurezza");
    }

    private HttpResult update(String ifMatch, String title) {
        return call(HttpMethod.PUT, "/api/v1/maintenance-tickets/" + ticketId, manager,
                "{\"title\":\"%s\",\"priority\":\"HIGH\"}".formatted(title), Map.of("If-Match", ifMatch));
    }

    private List<String> currentVersionAndTitle() {
        HttpResult current = call(HttpMethod.GET, "/api/v1/maintenance-tickets/" + ticketId, manager, null);
        return List.of(current.headers().getETag(), json(current.body()).get("title").asString());
    }
}
