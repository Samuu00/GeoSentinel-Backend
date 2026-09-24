package it.unical.geosentinel.trail.web.dto;

import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.application.dto.HazardCommands.CreateHazardReportCommand;
import it.unical.geosentinel.trail.application.dto.HazardCommands.HazardView;
import it.unical.geosentinel.trail.application.dto.PoiCommands.CreatePoiCommand;
import it.unical.geosentinel.trail.application.dto.PoiCommands.PoiView;
import it.unical.geosentinel.trail.application.dto.TrailCommands.CreateTrailCommand;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailSummary;
import it.unical.geosentinel.trail.application.dto.TrailCommands.UpdateTrailCommand;
import it.unical.geosentinel.trail.web.dto.HazardDtos.CreateHazardRequest;
import it.unical.geosentinel.trail.web.dto.HazardDtos.HazardResponse;
import it.unical.geosentinel.trail.web.dto.PoiDtos.CreatePoiRequest;
import it.unical.geosentinel.trail.web.dto.PoiDtos.PoiResponse;
import it.unical.geosentinel.trail.web.dto.TrailDtos.CreateTrailRequest;
import it.unical.geosentinel.trail.web.dto.TrailDtos.TrailDetailResponse;
import it.unical.geosentinel.trail.web.dto.TrailDtos.TrailSummaryResponse;
import it.unical.geosentinel.trail.web.dto.TrailDtos.UpdateTrailRequest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface TrailMapper {
    CreateTrailCommand toCommand(CreateTrailRequest request);
    UpdateTrailCommand toCommand(UpdateTrailRequest request);
    TrailDetailResponse toResponse(TrailDetail detail);
    TrailSummaryResponse toResponse(TrailSummary summary);
    CreatePoiCommand toCommand(CreatePoiRequest request);
    PoiResponse toResponse(PoiView view);
    CreateHazardReportCommand toCommand(CreateHazardRequest request);
    HazardResponse toResponse(HazardView view);
}
