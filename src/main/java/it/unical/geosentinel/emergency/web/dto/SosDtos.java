// DTO dell'API REST delle allerte SOS: i corpi delle richieste, con le regole di validazione, e il corpo
// della risposta.
package it.unical.geosentinel.emergency.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.RescueTeamResponse;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta usati da {@code SosController}.
 */
public class SosDtos {
    /** Corpo di POST /api/v1/sos: posizione obbligatoria e note mediche facoltative (al massimo 2000 caratteri). */
    public record RaiseSosRequest(
            @NotNull(message = "Location coordinates are required")
            @Valid
            GeoPointDto location,
            @Size(max = 2000, message = "Medical notes cannot exceed 2000 characters")
            String medicalNotes
    ){}

    /** Corpo di PUT /api/v1/sos/{id}/location. */
    public record UpdateSosLocationRequest(
            @NotNull(message = "Update location coordinates are required")
            @Valid
            GeoPointDto newLocation
    ){}

    /** Corpo di PATCH /api/v1/sos/{id}/dispatch. */
    public record DispatchTeamRequest(
            @NotNull(message = "Team ID is required")
            UUID teamId
    ){}

    /**
     * Allerta restituita dall'API. I campi senza {@code REQUIRED} possono essere null: note mediche assenti,
     * nessuna squadra ancora inviata, allerta non ancora chiusa.
     */
    public record SosAlertResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID hikerId,
            @Schema(requiredMode = REQUIRED) GeoPointDto location,
            @Schema(requiredMode = REQUIRED) Instant locationUpdatedAt,
            @Schema(requiredMode = REQUIRED) SosStatus status,
            String medicalNotes,
            RescueTeamResponse assignedTeam,
            Instant closedAt,
            @Schema(requiredMode = REQUIRED) Instant createdAt
    ){}
}
