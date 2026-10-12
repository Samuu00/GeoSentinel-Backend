package it.unical.geosentinel.refuge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.refuge.domain.RefugeAmenity;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;

import java.time.LocalDate;
import java.util.List;
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
            // tetto: un numero enorme farebbe sembrare il rifugio sempre libero (review di sicurezza di M8 dell'app)
            @Max(value = 1000, message = "Capacity must be at most 1000")
            Integer capacity,
            // niente managerId dal body: il gestore è chi crea il rifugio (sub del JWT)
            // Descrizione (V11): tutto facoltativo
            @Size(max = 2000, message = "Description must be at most 2000 characters")
            String description,
            @Min(value = 0, message = "Altitude must be at least 0")
            @Max(value = 5000, message = "Altitude must be at most 5000")
            Integer altitudeM,
            @Size(max = 200, message = "Season must be at most 200 characters")
            String season,
            @Size(max = 30, message = "Phone must be at most 30 characters")
            @Pattern(regexp = "^[+0-9 ()./-]*$", message = "Phone may contain only digits, spaces and + ( ) . / -")
            String phone,
            List<RefugeAmenity> amenities
    ) {}
    public record RefugeResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) GeoPointDto location,
            @Schema(requiredMode = REQUIRED) Integer capacity,
            @Schema(requiredMode = REQUIRED) UUID managerId,
            String description,
            Integer altitudeM,
            String season,
            String phone,
            // sempre presente, eventualmente vuota; nell'ordine dell'enum
            @Schema(requiredMode = REQUIRED) List<RefugeAmenity> amenities
    ) {}
    public record UpdateCapacityRequest(
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            // tetto: un numero enorme farebbe sembrare il rifugio sempre libero (review di sicurezza di M8 dell'app)
            @Max(value = 1000, message = "Capacity must be at most 1000")
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
