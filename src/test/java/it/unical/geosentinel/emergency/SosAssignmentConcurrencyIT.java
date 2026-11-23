// Test di integrazione della concorrenza sull'invio delle squadre di soccorso, con chiamate HTTP vere e
// PostGIS e Redis in Testcontainers. Un'allerta SOS può ricevere una sola squadra e una squadra può essere
// in missione su una sola allerta: il servizio prende lock pessimistici sempre nello stesso ordine
// (prima l'allerta, poi la squadra), così due operatori in parallelo non creano assegnazioni doppie né deadlock.
package it.unical.geosentinel.emergency;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica l'esclusività dell'invio di una squadra su un'allerta, con richieste concorrenti ripetute più
 * volte per far emergere eventuali race condition.
 */
class SosAssignmentConcurrencyIT extends AbstractIntegrationTest {

    /** Corpo di una richiesta SOS con posizione e una nota medica. */
    private static final String SOS_BODY = """
            {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"Caviglia"}
            """;

    /**
     * Dieci operatori inviano contemporaneamente dieci squadre diverse alla stessa allerta. Il risultato
     * atteso è un solo 200, nove 409 con slug {@code alert-already-taken} e una sola squadra in missione.
     */
    @RepeatedTest(5)
    void tenOperatorsDispatchingToTheSameAlertGiveOneSuccess() throws Exception {
        UUID alertId = raiseSos(obtainToken(Role.HIKER));
        List<UUID> teams = IntStream.range(0, 10).mapToObj(i -> insertTeam("Squadra " + i)).toList();
        List<String> operators = IntStream.range(0, 10).mapToObj(i -> obtainToken(Role.RESCUE_OPERATOR)).toList();

        List<HttpResult> results = runConcurrently(10, i -> call(HttpMethod.PATCH, "/api/v1/sos/" + alertId + "/dispatch",
                operators.get(i), "{\"teamId\":\"%s\"}".formatted(teams.get(i))));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .hasSize(9)
                .allSatisfy(r -> assertThat(json(r.body()).get("type").asString()).endsWith("/alert-already-taken"));
        assertThat(count("SELECT count(*) FROM rescue_teams WHERE status = 'ON_MISSION'")).isEqualTo(1);
    }

    /**
     * Due operatori inviano contemporaneamente la stessa squadra a due allerte diverse. Solo una richiesta
     * deve riuscire (200) e solo un'allerta deve passare allo stato {@code DISPATCHED}.
     */
    @RepeatedTest(5)
    void theSameTeamDispatchedToTwoAlertsGoesToOnlyOne() throws Exception {
        List<UUID> alerts = List.of(raiseSos(obtainToken(Role.HIKER)), raiseSos(obtainToken(Role.HIKER)));
        UUID teamId = insertTeam("Unica squadra");
        List<String> operators = List.of(obtainToken(Role.RESCUE_OPERATOR), obtainToken(Role.RESCUE_OPERATOR));

        List<HttpResult> results = runConcurrently(2, i -> call(HttpMethod.PATCH,
                "/api/v1/sos/" + alerts.get(i) + "/dispatch", operators.get(i), "{\"teamId\":\"%s\"}".formatted(teamId)));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        // La seconda richiesta trova la squadra già in missione: è una violazione di una regola di dominio,
        // quindi la risposta è 422 e non 409.
        assertThat(results).filteredOn(r -> r.status() == 422).hasSize(1);
        assertThat(count("SELECT count(*) FROM sos_alerts WHERE status = 'DISPATCHED'")).isEqualTo(1);
    }

    /**
     * Lancia un SOS a nome dell'escursionista e controlla che venga creato.
     *
     * @param hikerToken l'access token dell'escursionista
     * @return l'id dell'allerta creata
     */
    private UUID raiseSos(String hikerToken) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/sos", hikerToken, SOS_BODY);
        assertThat(result.status()).isEqualTo(201);
        return UUID.fromString(json(result.body()).get("id").asString());
    }

    /**
     * Esegue una query di conteggio.
     *
     * @param sql la query, che deve restituire un solo intero
     * @return il conteggio
     */
    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
