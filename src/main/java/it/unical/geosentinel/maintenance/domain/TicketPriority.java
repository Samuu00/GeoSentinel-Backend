// Enum delle priorità di un ticket di manutenzione, salvato come stringa in maintenance_tickets.priority.
package it.unical.geosentinel.maintenance.domain;

/**
 * Priorità di un ticket, dalla più bassa alla più alta. Essendo salvata come stringa, nel database non si
 * può ordinare per gravità: l'ordine alfabetico è diverso.
 */
public enum TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
