// Mapper MapStruct della feature media: converte la richiesta di caricamento nel comando del service e
// le viste del service nelle risposte REST.
package it.unical.geosentinel.media.web.dto;

import it.unical.geosentinel.media.application.dto.MediaCommands.InitUploadCommand;
import it.unical.geosentinel.media.application.dto.MediaCommands.MediaFileView;
import it.unical.geosentinel.media.application.dto.MediaCommands.UploadSessionView;
import it.unical.geosentinel.media.web.dto.MediaDtos.InitUploadRequest;
import it.unical.geosentinel.media.web.dto.MediaDtos.MediaResponse;
import it.unical.geosentinel.media.web.dto.MediaDtos.UploadSessionResponse;
import org.mapstruct.Mapper;

/**
 * Interfaccia da cui MapStruct genera, in fase di compilazione, un bean Spring con le conversioni.
 */
@Mapper(componentModel = "spring")
public interface MediaMapper {
    /** Richiesta di caricamento verso il comando. */
    InitUploadCommand toCommand(InitUploadRequest request);
    /** Sessione di caricamento verso la risposta REST. */
    UploadSessionResponse toResponse(UploadSessionView view);
    /** Metadati del file verso la risposta REST. */
    MediaResponse toResponse(MediaFileView view);
}
