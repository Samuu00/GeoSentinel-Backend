// Comandi, filtri e viste dello strato applicativo della manutenzione: portano i dati dai controller a
// MaintenanceService e i risultati nella direzione opposta.
package it.unical.geosentinel.maintenance.application.dto;

import it.unical.geosentinel.maintenance.domain.MaintenanceTicket;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Contenitore dei record usati da {@code MaintenanceService}.
 */
public class MaintenanceTicketCommands {
    /**
     * Apertura di un ticket. Esattamente uno fra trailId, refugeId e hazardReportId deve essere valorizzato:
     * il servizio lo verifica e rifiuta gli altri casi.
     *
     * @param title titolo breve del problema
     * @param description descrizione facoltativa
     * @param priority priorità
     * @param trailId sentiero interessato
     * @param refugeId rifugio interessato
     * @param hazardReportId segnalazione di pericolo da cui nasce il ticket
     */
    public record CreateMaintenanceTicketCommand(
            String title,
            String description,
            TicketPriority priority,
            UUID trailId,
            UUID refugeId,
            UUID hazardReportId
    ){}

    /**
     * Modifica dei dati descrittivi di un ticket ancora aperto.
     *
     * @param title nuovo titolo
     * @param description nuova descrizione, può essere null
     * @param priority nuova priorità
     */
    public record UpdateTicketCommand(
            String title,
            String description,
            TicketPriority priority
    ){}

    /**
     * Filtri della ricerca dei ticket; un campo null significa "nessun filtro" su quel campo.
     *
     * @param status stato richiesto
     * @param priority priorità richiesta
     * @param assignedTo utente a cui il ticket è assegnato
     */
    public record TicketQuery(
            TicketStatus status,
            TicketPriority priority,
            UUID assignedTo
    ){}

    /**
     * Vista di un ticket restituita dal servizio, con i nomi di sentiero e rifugio già risolti.
     * Il campo version serve al controller per costruire l'ETag della risposta; nel corpo JSON non compare.
     */
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
        /**
         * Costruisce la vista a partire dall'entità. Legge il nome di sentiero e rifugio, quindi va chiamato
         * dentro una transazione o con le associazioni già caricate.
         *
         * @param t ticket da convertire
         * @return la vista del ticket
         */
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
