// Enum degli stati di un ticket di manutenzione, salvato come stringa in maintenance_tickets.status.
package it.unical.geosentinel.maintenance.domain;

/**
 * Ciclo di vita di un ticket; le transizioni ammesse sono controllate da {@link MaintenanceTicket}.
 */
public enum TicketStatus {
    /** Appena aperto, senza assegnatario. */
    OPEN,
    /** Assegnato a un utente, lavori non ancora iniziati. */
    ASSIGNED,
    /** Lavori in corso. */
    IN_PROGRESS,
    /** Lavoro concluso. */
    RESOLVED,
    /** Ticket annullato. */
    CANCELLED
}
