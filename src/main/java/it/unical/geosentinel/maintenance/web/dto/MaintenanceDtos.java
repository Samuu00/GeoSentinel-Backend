// DTO dell'API REST dei ticket di manutenzione: corpi delle richieste, con le regole di validazione, e
// corpo della risposta.
package it.unical.geosentinel.maintenance.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta usati da {@code MaintenanceController}.
 */
public class MaintenanceDtos {
    /**
     * Corpo di POST /api/v1/maintenance-tickets. Va indicato esattamente uno fra trailId, refugeId e
     * hazardReportId: il controllo lo fa il servizio, che altrimenti risponde 422.
     */
    public record CreateTicketRequest(
            @NotBlank(message = "Title is required")
            @Size(min = 3, max = 150, message = "Title must be between 3 and 150 characters")
            String title,

            @Size(max = 2000, message = "Description cannot exceed 2000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TicketPriority priority,

            UUID trailId,
            UUID refugeId,
            UUID hazardReportId
    ) {}

    /** Corpo di PUT /api/v1/maintenance-tickets/{id}. */
    public record UpdateTicketRequest(
            @NotBlank(message = "Title is required")
            @Size(min = 3, max = 150, message = "Title must be between 3 and 150 characters")
            String title,

            @Size(max = 2000, message = "Description cannot exceed 2000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TicketPriority priority
    ) {}

    /** Corpo di PATCH /api/v1/maintenance-tickets/{id}/assign. */
    public record AssignTicketRequest(
            @NotNull(message = "Operator ID is required")
            UUID operatorId
    ) {}

    /** Corpo di PATCH /api/v1/maintenance-tickets/{id}/status. */
    public record UpdateTicketStatusRequest(
            @NotNull(message = "Status is required")
            TicketStatus status
    ) {}

    /**
     * Ticket restituito dall'API. La versione non compare nel corpo: viaggia nell'header ETag.
     */
    public record TicketResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String title,
            String description,
            @Schema(requiredMode = REQUIRED) TicketPriority priority,
            @Schema(requiredMode = REQUIRED) TicketStatus status,
            UUID trailId,
            String trailName,
            UUID refugeId,
            String refugeName,
            UUID hazardReportId,
            UUID assignedTo,
            Instant resolvedAt,
            @Schema(requiredMode = REQUIRED) Instant createdAt
    ) {}
}
