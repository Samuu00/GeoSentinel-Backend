// Mapper MapStruct della feature trail: converte le richieste REST nei comandi del service e le viste
// del service nelle risposte REST. Le conversioni delle geometrie sono delegate a GeometryMapper.
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

/**
 * Interfaccia da cui MapStruct genera, in fase di compilazione, un bean Spring con le conversioni.
 * Ogni metodo copia i campi con lo stesso nome tra i due record.
 */
@Mapper(componentModel = "spring", uses = {GeometryMapper.class})
public interface TrailMapper {
    /** Richiesta di creazione di un sentiero verso il comando, con il tracciato convertito in JTS. */
    CreateTrailCommand toCommand(CreateTrailRequest request);
    /** Richiesta di modifica di un sentiero verso il comando. */
    UpdateTrailCommand toCommand(UpdateTrailRequest request);
    /** Dettaglio del sentiero verso la risposta REST. */
    TrailDetailResponse toResponse(TrailDetail detail);
    /** Riepilogo del sentiero verso la risposta REST. */
    TrailSummaryResponse toResponse(TrailSummary summary);
    /** Richiesta di creazione di un POI verso il comando, con la posizione convertita in JTS. */
    CreatePoiCommand toCommand(CreatePoiRequest request);
    /** Vista del POI verso la risposta REST. */
    PoiResponse toResponse(PoiView view);
    /** Richiesta di creazione di una segnalazione verso il comando. */
    CreateHazardReportCommand toCommand(CreateHazardRequest request);
    /** Vista della segnalazione verso la risposta REST; l'id dell'autore viene scartato. */
    HazardResponse toResponse(HazardView view);
}
