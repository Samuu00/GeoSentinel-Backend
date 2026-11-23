// Test unitario delle entità di dominio SosAlert (richiesta di soccorso) e RescueTeam (squadra di soccorso).
// Controlla il ciclo di vita di un'allerta (invio della squadra, risoluzione, annullamento, aggiornamento
// della posizione) e lo stato della squadra che ne consegue, senza database e senza Spring.
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

/**
 * Verifica le transizioni di stato di {@link SosAlert} e il loro effetto su {@link RescueTeam}.
 */
class SosAlertTest {

    /** Fabbrica di geometrie in coordinate WGS84 (SRID 4326). */
    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    /** Id dell'operatore di soccorso che invia le squadre. */
    private static final UUID OPERATOR = UUID.randomUUID();

    /** Istante di creazione delle allerte di prova. */
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    /**
     * Crea un punto.
     *
     * @param lon la longitudine
     * @param lat la latitudine
     * @return il punto con SRID 4326
     */
    private static Point point(double lon, double lat) {
        return GEO.createPoint(new Coordinate(lon, lat));
    }

    /**
     * Crea un'allerta attiva di un escursionista qualsiasi, senza note mediche.
     *
     * @return l'allerta appena lanciata
     */
    private static SosAlert alert() {
        return SosAlert.raise(UUID.randomUUID(), null, point(11.85, 46.43), null, NOW);
    }

    /**
     * Crea una squadra di soccorso disponibile.
     *
     * @return la squadra nello stato iniziale
     */
    private static RescueTeam team() {
        return new RescueTeam("Soccorso Alpino Val di Fassa", "FASSA-1", point(11.77, 46.43), "+39 0462 000000");
    }

    /**
     * Inviare una squadra porta l'allerta in {@code DISPATCHED}, la squadra in {@code ON_MISSION} e registra
     * l'operatore. Un secondo invio sulla stessa allerta deve fallire con {@link AlertAlreadyTakenException}.
     */
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

    /** Una squadra già in missione non può essere inviata a un'altra allerta: si attende una {@link BusinessRuleViolationException}. */
    @Test
    void busyTeamCannotBeDispatchedTwice() {
        RescueTeam team = team();
        alert().dispatch(team, OPERATOR);

        assertThatThrownBy(() -> alert().dispatch(team, OPERATOR)).isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * Sia la risoluzione sia l'annullamento chiudono l'allerta registrando l'istante di chiusura e rendono di
     * nuovo disponibile la squadra che era stata inviata.
     */
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

    /**
     * Un'allerta già chiusa non accetta più aggiornamenti della posizione, né una nuova risoluzione, né un
     * annullamento: ogni tentativo lancia una {@link BusinessRuleViolationException}.
     */
    @Test
    void closedAlertRejectsLocationUpdatesAndResolve() {
        SosAlert alert = alert();
        alert.resolve(NOW);

        assertThatThrownBy(() -> alert.updateLocation(point(11.86, 46.44), NOW)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> alert.resolve(NOW)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> alert.cancel(NOW)).isInstanceOf(BusinessRuleViolationException.class);
    }

    /** L'aggiornamento della posizione salva il nuovo punto e l'istante in cui è stato ricevuto. */
    @Test
    void locationUpdateRecordsWhenItHappened() {
        SosAlert alert = alert();
        alert.updateLocation(point(11.86, 46.44), NOW.plusSeconds(30));

        assertThat(alert.getLocation().getX()).isEqualTo(11.86);
        assertThat(alert.getLocationUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    /**
     * Una squadra in missione non può essere rimessa a mano nello stato disponibile: torna libera solo quando
     * l'allerta viene chiusa.
     */
    @Test
    void teamOnMissionCannotBeMadeAvailableByHand() {
        RescueTeam team = team();
        alert().dispatch(team, OPERATOR);

        assertThatThrownBy(team::setAvailable).isInstanceOf(BusinessRuleViolationException.class);
    }
}
