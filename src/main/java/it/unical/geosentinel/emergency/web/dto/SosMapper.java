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

@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface SosMapper {
    RaiseSosCommand toCommand(RaiseSosRequest request);
    UpdateLocationCommand toCommand(UpdateSosLocationRequest request);
    DispatchTeamCommand toCommand(DispatchTeamRequest request);
    CreateRescueTeamCommand toCommand(CreateRescueTeamRequest request);
    SosAlertResponse toResponse(SosAlertView view);
    RescueTeamResponse toResponse(RescueTeamView view);
}
