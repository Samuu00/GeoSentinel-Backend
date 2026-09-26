package it.unical.geosentinel.maintenance.infrastructure.persistence;

import it.unical.geosentinel.maintenance.domain.MaintenanceTicket;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, UUID> {
    @Query("""
            SELECT t FROM MaintenanceTicket t
            LEFT JOIN FETCH t.trail
            LEFT JOIN FETCH t.refuge
            WHERE t.id = :id
            """)
    Optional<MaintenanceTicket> findByIdWithDetails(@Param("id") UUID id);

    List<MaintenanceTicket> findByTrailIdOrderByCreatedAtDesc(UUID trailId);

    // trail e refuge caricati insieme: TicketView ne legge il nome, altrimenti una query per ticket (N+1)
    @EntityGraph(attributePaths = {"trail", "refuge"})
    @Query("""
            SELECT t FROM MaintenanceTicket t
            WHERE (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:assignedTo IS NULL OR t.assignedTo = :assignedTo)
            """)
    Page<MaintenanceTicket> searchTickets(
            @Param("status") TicketStatus status,
            @Param("priority") TicketPriority priority,
            @Param("assignedTo") UUID assignedTo,
            Pageable pageable
    );
}
