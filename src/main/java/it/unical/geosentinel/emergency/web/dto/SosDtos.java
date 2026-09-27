package it.unical.geosentinel.emergency.web.dto;

import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.RescueTeamResponse;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class SosDtos {
    public record RaiseSosRequest(
            @NotNull(message = "Location coordinates are required")
            @Valid
            GeoPointDto location,
            @Size(max = 2000, message = "Medical notes cannot exceed 2000 characters")
            String medicalNotes
    ){}

    public record UpdateSosLocationRequest(
            @NotNull(message = "Update location coordinates are required")
            @Valid
            GeoPointDto newLocation
    ){}

    public record DispatchTeamRequest(
            @NotNull(message = "Team ID is required")
            UUID teamId
    ){}

    public record SosAlertResponse(
            UUID id,
            UUID hikerId,
            GeoPointDto location,
            Instant locationUpdatedAt,
            SosStatus status,
            String medicalNotes,
            RescueTeamResponse assignedTeam,
            Instant closedAt,
            Instant createdAt
    ){}
}
