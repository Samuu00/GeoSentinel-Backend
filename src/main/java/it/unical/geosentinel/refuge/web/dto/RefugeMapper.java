package it.unical.geosentinel.refuge.web.dto;

import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.AvailabilityView;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingCommand;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingView;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.CreateRefugeCommand;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.RefugeView;
import it.unical.geosentinel.refuge.web.dto.BookingDtos.BookingResponse;
import it.unical.geosentinel.refuge.web.dto.BookingDtos.CreateBookingRequest;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.AvailableResponse;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.CreateRefugeRequest;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.RefugeResponse;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.mapstruct.Mapper;

import java.util.UUID;

@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface RefugeMapper {
    CreateRefugeCommand toCommand(CreateRefugeRequest request, UUID managerId);
    BookingCommand toCommand(CreateBookingRequest request);
    RefugeResponse toResponse(RefugeView view);
    AvailableResponse toResponse(AvailabilityView view);
    BookingResponse toResponse(BookingView view);
}
