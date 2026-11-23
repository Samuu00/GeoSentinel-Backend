// Mapper MapStruct della feature refuge: converte le richieste REST nei comandi del service e le viste
// del service nelle risposte REST, delegando le geometrie a GeometryMapper.
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

/**
 * Interfaccia da cui MapStruct genera, in fase di compilazione, un bean Spring con le conversioni.
 */
@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface RefugeMapper {
    /**
     * Converte la richiesta di creazione aggiungendo il gestore, che non arriva dal body.
     *
     * @param request   richiesta validata
     * @param managerId id dell'utente che crea il rifugio
     * @return il comando
     */
    CreateRefugeCommand toCommand(CreateRefugeRequest request, UUID managerId);
    /** Richiesta di prenotazione verso il comando. */
    BookingCommand toCommand(CreateBookingRequest request);
    /** Vista del rifugio verso la risposta REST. */
    RefugeResponse toResponse(RefugeView view);
    /** Disponibilità verso la risposta REST. */
    AvailableResponse toResponse(AvailabilityView view);
    /** Vista della prenotazione verso la risposta REST. */
    BookingResponse toResponse(BookingView view);
}
