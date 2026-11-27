// DTO dell'API REST delle prenotazioni: la richiesta di creazione, con i vincoli di Bean Validation, e la
// risposta inviata al client.
package it.unical.geosentinel.refuge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import it.unical.geosentinel.refuge.domain.BookingStatus;

import java.time.LocalDate;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta delle prenotazioni.
 */
public class BookingDtos {
    /**
     * Corpo della richiesta di prenotazione. Sulle date non c'è {@code @FutureOrPresent}: userebbe il fuso
     * orario della JVM invece del Clock UTC, e un ritentativo arrivato dopo il giorno di arrivo
     * riceverebbe un 400 invece della prenotazione già creata. Le date le valida il service.
     *
     * @param refugeId rifugio
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @param guests   numero di ospiti, da 1 a 20
     */
    public record CreateBookingRequest(
            @NotNull(message = "Refuge ID is required")
            UUID refugeId,
            @NotNull(message = "Check-in date is required")
            LocalDate checkIn,
            @NotNull(message = "Check-out date is required")
            LocalDate checkOut,
            @NotNull(message = "Number of guests is required")
            @Min(value = 1, message = "Must book at least 1 guest")
            @Max(value = 20, message = "Single booking cannot exceed 20 guests")
            Integer guests
    ) {}
    /**
     * Prenotazione restituita al client.
     *
     * @param id       identificativo
     * @param refugeId rifugio
     * @param userId   utente che ha prenotato
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @param guests   numero di ospiti
     * @param status   stato
     * @param totalPriceCents totale in centesimi copiato al momento della prenotazione, {@code null} se il
     *                        rifugio non aveva un prezzo
     */
    public record BookingResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID refugeId,
            @Schema(requiredMode = REQUIRED) UUID userId,
            @Schema(requiredMode = REQUIRED) LocalDate checkIn,
            @Schema(requiredMode = REQUIRED) LocalDate checkOut,
            @Schema(requiredMode = REQUIRED) int guests,
            @Schema(requiredMode = REQUIRED) BookingStatus status,
            Integer totalPriceCents
    ) {}
}
