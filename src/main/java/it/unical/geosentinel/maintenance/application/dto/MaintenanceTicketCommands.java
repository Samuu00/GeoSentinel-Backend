package it.unical.geosentinel.maintenance.application.dto;

import it.unical.geosentinel.maintenance.domain.MaintenanceTicket;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public class MaintenanceTicketCommands {
    public record CreateMaintenanceTicketCommand(
            String title,
            String description,
            TicketPriority priority,
            UUID trailId,
            UUID refugeId,
            UUID hazardReportId
    ){}

    public record UpdateTicketCommand(
            String title,
            String description,
            TicketPriority priority
    ){}

    public record TicketQuery(
            TicketStatus status,
            TicketPriority priority,
            UUID assignedTo
    ){}

    // version serve al controller per l'ETag; la response non la espone nel body
    public record TicketView(
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
            Instant createdAt,
            Long version
    ){
        public static TicketView from(MaintenanceTicket t) {
            return new TicketView(
                    t.getId(),
                    t.getTitle(),
                    t.getDescription(),
                    t.getPriority(),
                    t.getStatus(),
                    t.getTrail() != null ? t.getTrail().getId() : null,
                    t.getTrail() != null ? t.getTrail().getName() : null,
                    t.getRefuge() != null ? t.getRefuge().getId() : null,
                    t.getRefuge() != null ? t.getRefuge().getName() : null,
                    t.getHazardReport() != null ? t.getHazardReport().getId() : null,
                    t.getAssignedTo(),
                    t.getResolvedAt(),
                    t.getCreatedAt(),
                    t.getVersion()
            );
        }
    }
}
