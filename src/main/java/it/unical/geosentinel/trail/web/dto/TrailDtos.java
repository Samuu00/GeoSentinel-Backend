package it.unical.geosentinel.trail.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;

import java.util.UUID;

public class TrailDtos {
    public record CreateTrailRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 3, max = 150, message = "Name must be between 3 and 150 characters")
            String name,
            String description,
            @NotNull(message = "Difficulty is required")
            Difficulty difficulty,
            @NotNull(message = "Path geometry is required")
            @Valid
            GeoJsonLineString path
    ) {}

    public record UpdateTrailRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 3, max = 150, message = "Name must be between 3 and 150 characters")
            String name,
            String description,
            @NotNull(message = "Difficulty is required")
            Difficulty difficulty,
            @NotNull(message = "Path geometry is required")
            @Valid
            GeoJsonLineString path
    ) {}

    public record UpdateTrailStatusRequest(@NotNull(message = "Status is required") TrailStatus status) {}
    public record TrailDetailResponse(
            UUID id,
            String name,
            String description,
            Difficulty difficulty,
            TrailStatus status,
            GeoJsonLineString path,
            Double lengthM
    ) {}

    public record TrailSummaryResponse(UUID id, String name, Difficulty difficulty, TrailStatus status, Double lengthM) {}
}
