// Mapper MapStruct della manutenzione: converte i DTO dell'API nei comandi del servizio e le viste nei DTO di
// risposta. L'implementazione è generata in compilazione come bean Spring (opzione impostata nel pom.xml).
package it.unical.geosentinel.maintenance.web.dto;

import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.CreateMaintenanceTicketCommand;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.UpdateTicketCommand;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.CreateTicketRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.TicketResponse;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.UpdateTicketRequest;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Conversioni tra lo strato web e lo strato applicativo dei ticket. Il campo version di TicketView non ha
 * corrispondente in TicketResponse e viene quindi tralasciato.
 */
@Mapper
public interface MaintenanceMapper {
    /** Richiesta di apertura verso il comando del servizio. */
    CreateMaintenanceTicketCommand toCommand(CreateTicketRequest request);
    /** Richiesta di modifica verso il comando del servizio. */
    UpdateTicketCommand toCommand(UpdateTicketRequest request);
    /** Vista del ticket verso il DTO di risposta. */
    TicketResponse toResponse(TicketView view);
    /** Conversione di un elenco di viste. */
    List<TicketResponse> toResponses(List<TicketView> views);
}
