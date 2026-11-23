// DTO dell'API REST per le segnalazioni di pericolo: il corpo delle richieste di creazione e di cambio di
// stato, con i vincoli di Bean Validation, e la risposta inviata al client.
package it.unical.geosentinel.trail.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.trail.domain.HazardStatus;
import it.unical.geosentinel.trail.domain.HazardType;
import it.unical.geosentinel.trail.domain.Severity;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta delle segnalazioni.
 */
public class HazardDtos {
    /**
     * Corpo della richiesta di creazione di una segnalazione.
     *
     * @param trailId     sentiero di riferimento, facoltativo
     * @param type        tipo di pericolo
     * @param severity    gravità
     * @param description descrizione facoltativa, al massimo 1000 caratteri
     * @param location    posizione del pericolo
     */
    public record CreateHazardRequest(
            UUID trailId,
            @NotNull(message = "Hazard type is required")
            HazardType type,
            @NotNull(message = "Severity is required")
            Severity severity,
            @Size(max = 1000, message = "Description must not exceed 1000 characters")
            String description,
            @NotNull(message = "Location is required")
            @Valid
            GeoPointDto location
    ) {}

    /**
     * Corpo della richiesta di cambio di stato, usata dal gestore del parco.
     *
     * @param status nuovo stato
     */
    public record UpdateHazardStatusRequest(
            @NotNull(message = "Status is required")
            HazardStatus status
    ) {}

    /**
     * Segnalazione restituita al client. Non contiene l'id dell'autore: sapere chi ha segnalato cosa,
     * dove e quando è un dato personale, perché permette di ricostruire gli spostamenti di una persona.
     *
     * @param id          identificativo
     * @param trailId     sentiero di riferimento, può essere {@code null}
     * @param type        tipo di pericolo
     * @param severity    gravità
     * @param description descrizione, può essere {@code null}
     * @param location    posizione
     * @param status      stato corrente
     * @param createdAt   data della segnalazione, mostrata nella lista "Le mie segnalazioni" dell'app
     */
    public record HazardResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            UUID trailId,
            @Schema(requiredMode = REQUIRED) HazardType type,
            @Schema(requiredMode = REQUIRED) Severity severity,
            String description,
            @Schema(requiredMode = REQUIRED) GeoPointDto location,
            @Schema(requiredMode = REQUIRED) HazardStatus status,
            @Schema(requiredMode = REQUIRED) Instant createdAt
    ) {}
}
