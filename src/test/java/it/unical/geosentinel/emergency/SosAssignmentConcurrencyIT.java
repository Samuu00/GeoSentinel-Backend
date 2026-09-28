package it.unical.geosentinel.emergency;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// Invio esclusivo della squadra: lock pessimistici con ordine fisso allerta → squadra (M4.3)
class SosAssignmentConcurrencyIT extends AbstractIntegrationTest {

    private static final String SOS_BODY = """
            {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"Caviglia"}
            """;

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

    @RepeatedTest(5)
    void theSameTeamDispatchedToTwoAlertsGoesToOnlyOne() throws Exception {
        List<UUID> alerts = List.of(raiseSos(obtainToken(Role.HIKER)), raiseSos(obtainToken(Role.HIKER)));
        UUID teamId = insertTeam("Unica squadra");
        List<String> operators = List.of(obtainToken(Role.RESCUE_OPERATOR), obtainToken(Role.RESCUE_OPERATOR));

        List<HttpResult> results = runConcurrently(2, i -> call(HttpMethod.PATCH,
                "/api/v1/sos/" + alerts.get(i) + "/dispatch", operators.get(i), "{\"teamId\":\"%s\"}".formatted(teamId)));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        // la seconda trova la squadra già in missione: regola di dominio, 422
        assertThat(results).filteredOn(r -> r.status() == 422).hasSize(1);
        assertThat(count("SELECT count(*) FROM sos_alerts WHERE status = 'DISPATCHED'")).isEqualTo(1);
    }

    private UUID raiseSos(String hikerToken) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/sos", hikerToken, SOS_BODY);
        assertThat(result.status()).isEqualTo(201);
        return UUID.fromString(json(result.body()).get("id").asString());
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
