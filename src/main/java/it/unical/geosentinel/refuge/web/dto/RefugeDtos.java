package it.unical.geosentinel.refuge.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

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
    public record RefugeResponse(UUID id, String name, GeoPointDto location, Integer capacity, UUID managerId) {}
    public record UpdateCapacityRequest(
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            Integer capacity
    ) {}
    public record AvailableResponse(
            UUID refugeId,
            Integer totalCapacity,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, Integer> availableBedsPerNight
    ) {}
}
