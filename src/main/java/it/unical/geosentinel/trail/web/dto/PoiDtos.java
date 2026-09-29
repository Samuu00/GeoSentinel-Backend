package it.unical.geosentinel.trail.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.trail.domain.PoiType;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
public class PoiDtos {
    public record CreatePoiRequest(
            UUID trailId,
            @NotBlank(message = "Name is required")
            @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
            String name,
            @NotNull(message = "Type is required")
            PoiType type,
            @NotNull(message = "Location is required")
            @Valid
            GeoPointDto location
    ) {}

    public record PoiResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            UUID trailId,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) PoiType type,
            @Schema(requiredMode = REQUIRED) GeoPointDto location
    ) {}
}
