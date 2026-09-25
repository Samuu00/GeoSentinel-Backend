package it.unical.geosentinel.refuge.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import it.unical.geosentinel.refuge.domain.BookingStatus;

import java.time.LocalDate;
import java.util.UUID;

public class BookingDtos {
    public record CreateBookingRequest(
            @NotNull(message = "Refuge ID is required")
            UUID refugeId,
            // niente @FutureOrPresent: userebbe il fuso della JVM invece del Clock UTC, e un retry arrivato
            // dopo il check-in riceverebbe 400 invece della prenotazione già creata (D12). Le date le valida il service
            @NotNull(message = "Check-in date is required")
            LocalDate checkIn,
            @NotNull(message = "Check-out date is required")
            LocalDate checkOut,
            @NotNull(message = "Number of guests is required")
            @Min(value = 1, message = "Must book at least 1 guest")
            @Max(value = 20, message = "Single booking cannot exceed 20 guests")
            Integer guests
    ) {}
    public record BookingResponse(
            UUID id,
            UUID refugeId,
            UUID userId,
            LocalDate checkIn,
            LocalDate checkOut,
            int guests,
            BookingStatus status
    ) {}
}
