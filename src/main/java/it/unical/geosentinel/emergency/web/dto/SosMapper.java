// Mapper MapStruct delle emergenze: converte i DTO dell'API nei comandi del servizio e le viste del servizio
// nei DTO di risposta. L'implementazione viene generata in fase di compilazione.
package it.unical.geosentinel.emergency.web.dto;

import it.unical.geosentinel.emergency.application.dto.SosCommands.CreateRescueTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.DispatchTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RaiseSosCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RescueTeamView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosAlertView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.UpdateLocationCommand;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.CreateRescueTeamRequest;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.RescueTeamResponse;
import it.unical.geosentinel.emergency.web.dto.SosDtos.DispatchTeamRequest;
import it.unical.geosentinel.emergency.web.dto.SosDtos.RaiseSosRequest;
import it.unical.geosentinel.emergency.web.dto.SosDtos.SosAlertResponse;
import it.unical.geosentinel.emergency.web.dto.SosDtos.UpdateSosLocationRequest;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.mapstruct.Mapper;

/**
 * Conversioni tra lo strato web e lo strato applicativo delle emergenze. Le coordinate passano da
 * {@code GeoPointDto} a {@code Point} di JTS (e viceversa) grazie a {@link GeometryMapper}, indicato in
 * {@code uses}. Il bean viene iniettato in {@code SosController} e {@code RescueTeamController}.
 */
@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface SosMapper {
    /** Richiesta di SOS verso il comando del servizio. */
    RaiseSosCommand toCommand(RaiseSosRequest request);
    /** Nuova posizione verso il comando del servizio. */
    UpdateLocationCommand toCommand(UpdateSosLocationRequest request);
    /** Richiesta di invio della squadra verso il comando del servizio. */
    DispatchTeamCommand toCommand(DispatchTeamRequest request);
    /** Richiesta di creazione della squadra verso il comando del servizio. */
    CreateRescueTeamCommand toCommand(CreateRescueTeamRequest request);
    /** Vista dell'allerta verso il DTO di risposta, compresa la squadra assegnata. */
    SosAlertResponse toResponse(SosAlertView view);
    /** Vista della squadra verso il DTO di risposta. */
    RescueTeamResponse toResponse(RescueTeamView view);
}
