// Repository Spring Data JPA dei ticket di manutenzione: lettura di dettaglio, elenco per sentiero e
// ricerca paginata con filtri facoltativi.
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

/**
 * Accesso alla tabella maintenance_tickets. Lo usa {@code MaintenanceService}.
 */
public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, UUID> {
    /**
     * Legge il ticket con sentiero e rifugio caricati nella stessa query.
     *
     * @param id id del ticket
     * @return il ticket, se esiste
     */
    @Query("""
            SELECT t FROM MaintenanceTicket t
            LEFT JOIN FETCH t.trail
            LEFT JOIN FETCH t.refuge
            WHERE t.id = :id
            """)
    Optional<MaintenanceTicket> findByIdWithDetails(@Param("id") UUID id);

    /**
     * @param trailId id del sentiero
     * @return i ticket del sentiero, dal più recente
     */
    List<MaintenanceTicket> findByTrailIdOrderByCreatedAtDesc(UUID trailId);

    /**
     * Ricerca paginata con filtri facoltativi: un parametro null disattiva il filtro corrispondente.
     * Sentiero e rifugio vengono caricati insieme ai ticket perché TicketView ne legge il nome; senza
     * l'EntityGraph partirebbe una query in più per ogni ticket (problema N+1).
     *
     * @return la pagina di ticket
     */
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
