// DTO dell'API REST dei rifugi: le richieste di creazione e di modifica dei posti letto, con i vincoli di
// Bean Validation, e le risposte con i dati del rifugio e la disponibilità per notte.
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

/**
 * Contenitore dei record di richiesta e risposta dei rifugi.
 */
public class RefugeDtos {
    /**
     * Corpo della richiesta di creazione di un rifugio. Non contiene il gestore: è l'utente che crea il
     * rifugio, ricavato dal JWT. I campi descrittivi dopo la capacità sono tutti facoltativi.
     *
     * @param name        nome, da 2 a 120 caratteri
     * @param location    posizione
     * @param capacity    posti letto, da 1 a 1000
     * @param description descrizione, al massimo 2000 caratteri
     * @param altitudeM   quota in metri, da 0 a 5000
     * @param season      periodo di apertura, al massimo 200 caratteri
     * @param phone       telefono, solo cifre, spazi e i simboli + ( ) . / -
     * @param amenities   servizi offerti
     */
    public record CreateRefugeRequest(
            @NotBlank(message = "Name is required")
            @Size(min = 2, max = 120, message = "Name must be between 2 and 120 characters")
            String name,
            // Senza @Valid i vincoli interni di GeoPointDto non verrebbero controllati (lat=200 sarebbe accettata).
            @NotNull(message = "Location is required")
            @Valid
            GeoPointDto location,
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            // Tetto alla capacità: un numero enorme farebbe sembrare il rifugio sempre libero.
            @Max(value = 1000, message = "Capacity must be at most 1000")
            Integer capacity,
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
    /**
     * Rifugio restituito al client.
     *
     * @param id          identificativo
     * @param name        nome
     * @param location    posizione
     * @param capacity    posti letto
     * @param managerId   gestore
     * @param description descrizione, può essere {@code null}
     * @param altitudeM   quota in metri, può essere {@code null}
     * @param season      periodo di apertura, può essere {@code null}
     * @param phone       telefono, può essere {@code null}
     * @param amenities   servizi, sempre presente (eventualmente vuota) e nell'ordine dell'enum
     */
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
            @Schema(requiredMode = REQUIRED) List<RefugeAmenity> amenities
    ) {}
    /**
     * Corpo della richiesta di modifica dei posti letto.
     *
     * @param capacity nuova capacità, da 1 a 1000
     */
    public record UpdateCapacityRequest(
            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be at least 1")
            // Stesso tetto della creazione: un numero enorme farebbe sembrare il rifugio sempre libero.
            @Max(value = 1000, message = "Capacity must be at most 1000")
            Integer capacity
    ) {}
    /**
     * Disponibilità di un rifugio per notte.
     *
     * @param refugeId              rifugio
     * @param totalCapacity         posti letto totali
     * @param from                  prima notte
     * @param to                    giorno di fine, escluso
     * @param availableBedsPerNight posti liberi per notte; in JSON le chiavi sono date ISO-8601
     *                              (ad esempio "2026-10-01")
     */
    public record AvailableResponse(
            @Schema(requiredMode = REQUIRED) UUID refugeId,
            @Schema(requiredMode = REQUIRED) Integer totalCapacity,
            @Schema(requiredMode = REQUIRED) LocalDate from,
            @Schema(requiredMode = REQUIRED) LocalDate to,
            @Schema(requiredMode = REQUIRED) Map<LocalDate, Integer> availableBedsPerNight
    ) {}
}
