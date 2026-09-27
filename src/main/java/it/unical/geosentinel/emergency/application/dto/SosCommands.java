package it.unical.geosentinel.emergency.application.dto;

import it.unical.geosentinel.emergency.domain.RescueTeam;
import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.domain.TeamStatus;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

public class SosCommands {
    public record RaiseSosCommand(
            Point location,
            String medicalNotes
    ){}

    public record UpdateLocationCommand(Point newLocation){}
    public record DispatchTeamCommand(UUID teamId){}

    public record CreateRescueTeamCommand(
            String name,
            String callSign,
            Point baseLocation,
            String contactNumber
    ){}

    // le view non contengono geometrie JTS (non serializzabili): GeoPointDto via GeometryMapper
    public record RescueTeamView(
            UUID id,
            String name,
            String callSign,
            TeamStatus status,
            GeoPointDto baseLocation,
            String contactNumber
    ){
        public static RescueTeamView from(RescueTeam team, GeometryMapper geo) {
            return new RescueTeamView(
                    team.getId(),
                    team.getName(),
                    team.getCallSign(),
                    team.getStatus(),
                    geo.toDto(team.getBaseLocation()),
                    team.getContactNumber()
            );
        }
    }

    public record SosAlertView(
            UUID id,
            UUID hikerId,
            GeoPointDto location,
            Instant locationUpdatedAt,
            SosStatus status,
            String medicalNotes,
            RescueTeamView assignedTeam,
            Instant closedAt,
            Instant createdAt
    ){
        public static SosAlertView from(SosAlert alert, GeometryMapper geo){
            return new SosAlertView(
                    alert.getId(),
                    alert.getHikerId(),
                    geo.toDto(alert.getLocation()),
                    alert.getLocationUpdatedAt(),
                    alert.getStatus(),
                    alert.getMedicalNotes(),
                    alert.getAssignedTeam() != null ? RescueTeamView.from(alert.getAssignedTeam(), geo) : null,
                    alert.getClosedAt(),
                    alert.getCreatedAt()
            );
        }
    }

    // created = false: retry (stessa Idempotency-Key) o allerta già attiva, il controller risponde 200 invece di 201
    public record SosRaiseResult(SosAlertView alert, boolean created) {}
}
