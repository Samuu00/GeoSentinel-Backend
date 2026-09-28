package it.unical.geosentinel.media.web.dto;

import it.unical.geosentinel.media.application.dto.MediaCommands.InitUploadCommand;
import it.unical.geosentinel.media.application.dto.MediaCommands.MediaFileView;
import it.unical.geosentinel.media.application.dto.MediaCommands.UploadSessionView;
import it.unical.geosentinel.media.web.dto.MediaDtos.InitUploadRequest;
import it.unical.geosentinel.media.web.dto.MediaDtos.MediaResponse;
import it.unical.geosentinel.media.web.dto.MediaDtos.UploadSessionResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MediaMapper {
    InitUploadCommand toCommand(InitUploadRequest request);
    UploadSessionResponse toResponse(UploadSessionView view);
    MediaResponse toResponse(MediaFileView view);
}
