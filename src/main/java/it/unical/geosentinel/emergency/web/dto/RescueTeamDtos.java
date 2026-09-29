package it.unical.geosentinel.emergency.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.emergency.domain.TeamStatus;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public class RescueTeamDtos {
    public record CreateRescueTeamRequest(
            @NotBlank(message = "Team name is required")
            @Size(min = 2, max = 100, message = "Team name must be between 2 and 100 characters")
            String name,

            @NotBlank(message = "Call sign is required")
            @Size(min = 2, max = 32, message = "Call sign must be between 2 and 32 characters")
            String callSign,

            @NotNull(message = "Base location coordinates are required")
            @Valid
            GeoPointDto baseLocation,

            @NotBlank(message = "Contact number is required")
            @Size(min = 5, max = 30, message = "Contact number must be between 5 and 30 characters")
            String contactNumber
    ) {}

    public record RescueTeamResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String callSign,
            @Schema(requiredMode = REQUIRED) TeamStatus status,
            @Schema(requiredMode = REQUIRED) GeoPointDto baseLocation,
            @Schema(requiredMode = REQUIRED) String contactNumber
    ) {}
}
