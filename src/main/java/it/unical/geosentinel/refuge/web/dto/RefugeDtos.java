package it.unical.geosentinel.refuge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public class RefugeDtos {
    public record CreateRefugeRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 2, max = 120, message = "Name must be between 2 and 120 characters")
            String name,
            // @Valid: senza, i vincoli di GeoPointDto non scattano (lat=200 accettata)
            @NotNull(message = "Location is required")
            @Valid
            GeoPointDto location,
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            Integer capacity
            // niente managerId dal body: il gestore è chi crea il rifugio (sub del JWT)
    ) {}
    public record RefugeResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) GeoPointDto location,
            @Schema(requiredMode = REQUIRED) Integer capacity,
            @Schema(requiredMode = REQUIRED) UUID managerId
    ) {}
    public record UpdateCapacityRequest(
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            Integer capacity
    ) {}
    // availableBedsPerNight: in JSON le chiavi sono stringhe ISO-8601 ("2026-10-01")
    public record AvailableResponse(
            @Schema(requiredMode = REQUIRED) UUID refugeId,
            @Schema(requiredMode = REQUIRED) Integer totalCapacity,
            @Schema(requiredMode = REQUIRED) LocalDate from,
            @Schema(requiredMode = REQUIRED) LocalDate to,
            @Schema(requiredMode = REQUIRED) Map<LocalDate, Integer> availableBedsPerNight
    ) {}
}
