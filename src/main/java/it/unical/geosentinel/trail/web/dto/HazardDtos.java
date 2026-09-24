package it.unical.geosentinel.trail.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.trail.domain.HazardStatus;
import it.unical.geosentinel.trail.domain.HazardType;
import it.unical.geosentinel.trail.domain.Severity;

import java.util.UUID;

public class HazardDtos {
    public record CreateHazardRequest(
            UUID trailId,
            @NotNull(message = "Hazard type is required")
            HazardType type,
            @NotNull(message = "Severity is required")
            Severity severity,
            @Size(max = 1000, message = "Description must not exceed 1000 characters")
            String description,
            @NotNull(message = "Location is required")
            @Valid
            GeoPointDto location
    ) {}

    public record UpdateHazardStatusRequest(
            @NotNull(message = "Status is required")
            HazardStatus status
    ) {}

    // Senza reporterId: chi ha segnalato cosa, dove e quando è un dato personale (traccia gli spostamenti)
    public record HazardResponse(
            UUID id,
            UUID trailId,
            HazardType type,
            Severity severity,
            String description,
            GeoPointDto location,
            HazardStatus status
    ) {}
}
