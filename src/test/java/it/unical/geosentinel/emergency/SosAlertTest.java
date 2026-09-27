package it.unical.geosentinel.emergency;

import it.unical.geosentinel.emergency.domain.RescueTeam;
import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.domain.TeamStatus;
import it.unical.geosentinel.shared.domain.exception.AlertAlreadyTakenException;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SosAlertTest {
    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private static Point point(double lon, double lat) {
        return GEO.createPoint(new Coordinate(lon, lat));
    }

    private static SosAlert alert() {
        return SosAlert.raise(UUID.randomUUID(), null, point(11.85, 46.43), null, NOW);
    }

    private static RescueTeam team() {
        return new RescueTeam("Soccorso Alpino Val di Fassa", "FASSA-1", point(11.77, 46.43), "+39 0462 000000");
    }

    @Test
    void dispatchDeploysTeamAndSecondDispatchIsAlreadyTaken() {
        SosAlert alert = alert();
        RescueTeam team = team();

        alert.dispatch(team, OPERATOR);

        assertThat(alert.getStatus()).isEqualTo(SosStatus.DISPATCHED);
        assertThat(team.getStatus()).isEqualTo(TeamStatus.ON_MISSION);
        assertThat(alert.getOperatorId()).isEqualTo(OPERATOR);
        assertThatThrownBy(() -> alert.dispatch(team(), OPERATOR)).isInstanceOf(AlertAlreadyTakenException.class);
    }

    @Test
    void busyTeamCannotBeDispatchedTwice() {
        RescueTeam team = team();
        alert().dispatch(team, OPERATOR);

        assertThatThrownBy(() -> alert().dispatch(team, OPERATOR)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void resolveAndCancelReleaseTheTeam() {
        SosAlert resolved = alert();
        RescueTeam first = team();
        resolved.dispatch(first, OPERATOR);
        resolved.resolve(NOW.plusSeconds(3600));

        assertThat(resolved.getStatus()).isEqualTo(SosStatus.RESOLVED);
        assertThat(resolved.getClosedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(first.getStatus()).isEqualTo(TeamStatus.AVAILABLE);

        SosAlert cancelled = alert();
        RescueTeam second = team();
        cancelled.dispatch(second, OPERATOR);
        cancelled.cancel(NOW.plusSeconds(60));

        assertThat(cancelled.getStatus()).isEqualTo(SosStatus.CANCELLED);
        assertThat(cancelled.getClosedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(second.getStatus()).isEqualTo(TeamStatus.AVAILABLE);
    }

    @Test
    void closedAlertRejectsLocationUpdatesAndResolve() {
        SosAlert alert = alert();
        alert.resolve(NOW);

        assertThatThrownBy(() -> alert.updateLocation(point(11.86, 46.44), NOW)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> alert.resolve(NOW)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> alert.cancel(NOW)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void locationUpdateRecordsWhenItHappened() {
        SosAlert alert = alert();
        alert.updateLocation(point(11.86, 46.44), NOW.plusSeconds(30));

        assertThat(alert.getLocation().getX()).isEqualTo(11.86);
        assertThat(alert.getLocationUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void teamOnMissionCannotBeMadeAvailableByHand() {
        RescueTeam team = team();
        alert().dispatch(team, OPERATOR);

        assertThatThrownBy(team::setAvailable).isInstanceOf(BusinessRuleViolationException.class);
    }
}
