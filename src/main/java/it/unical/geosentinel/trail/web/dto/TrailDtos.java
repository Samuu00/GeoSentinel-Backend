package it.unical.geosentinel.trail.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
    // lengthM: colonna generata, riletta dal flush prima del DTO (mai null in una risposta)
    public record TrailDetailResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            String description,
            @Schema(requiredMode = REQUIRED) Difficulty difficulty,
            @Schema(requiredMode = REQUIRED) TrailStatus status,
            @Schema(requiredMode = REQUIRED) GeoJsonLineString path,
            @Schema(requiredMode = REQUIRED) Double lengthM
    ) {}

    // Percorso A→B sui sentieri aperti (ADR-0027): parte e arriva sul vertice di sentiero più vicino ai due punti
    public record RouteResponse(
            @Schema(requiredMode = REQUIRED, description = "Path from the trail vertex nearest to the start to the one nearest to the destination")
            GeoJsonLineString path,
            @Schema(requiredMode = REQUIRED) Double lengthM,
            @Schema(requiredMode = REQUIRED, description = "Trails walked, in order") List<UUID> trailIds
    ) {}

    public record TrailSummaryResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) Difficulty difficulty,
            @Schema(requiredMode = REQUIRED) TrailStatus status,
            @Schema(requiredMode = REQUIRED) Double lengthM,
            @Schema(requiredMode = REQUIRED, description = "Simplified path (about 5 m tolerance) for drawing the trail on a map; the full path is in the trail detail")
            GeoJsonLineString path
    ) {}
}
