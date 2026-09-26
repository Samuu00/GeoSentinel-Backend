package it.unical.geosentinel.maintenance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public class MaintenanceDtos {
    // esattamente uno tra trailId, refugeId e hazardReportId: lo verifica il service (422)
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

    public record UpdateTicketRequest(
            @NotBlank(message = "Title is required")
            @Size(min = 3, max = 150, message = "Title must be between 3 and 150 characters")
            String title,

            @Size(max = 2000, message = "Description cannot exceed 2000 characters")
            String description,

            @NotNull(message = "Priority is required")
            TicketPriority priority
    ) {}

    public record AssignTicketRequest(
            @NotNull(message = "Operator ID is required")
            UUID operatorId
    ) {}

    public record UpdateTicketStatusRequest(
            @NotNull(message = "Status is required")
            TicketStatus status
    ) {}

    // niente version: va nell'ETag
    public record TicketResponse(
            UUID id,
            String title,
            String description,
            TicketPriority priority,
            TicketStatus status,
            UUID trailId,
            String trailName,
            UUID refugeId,
            String refugeName,
            UUID hazardReportId,
            UUID assignedTo,
            Instant resolvedAt,
            Instant createdAt
    ) {}
}
