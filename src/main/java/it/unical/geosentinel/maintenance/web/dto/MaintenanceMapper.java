package it.unical.geosentinel.maintenance.web.dto;

import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.CreateMaintenanceTicketCommand;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.UpdateTicketCommand;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.CreateTicketRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.TicketResponse;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.UpdateTicketRequest;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper
public interface MaintenanceMapper {
    CreateMaintenanceTicketCommand toCommand(CreateTicketRequest request);
    UpdateTicketCommand toCommand(UpdateTicketRequest request);
    TicketResponse toResponse(TicketView view);
    List<TicketResponse> toResponses(List<TicketView> views);
}
