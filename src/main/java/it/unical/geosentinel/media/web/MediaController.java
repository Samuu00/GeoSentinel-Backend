package it.unical.geosentinel.media.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.media.application.MediaService;
import it.unical.geosentinel.media.application.dto.MediaCommands.MediaFileView;
import it.unical.geosentinel.media.application.dto.MediaCommands.UploadSessionView;
import it.unical.geosentinel.media.domain.MediaTargetType;
import it.unical.geosentinel.media.web.dto.MediaDtos.DownloadUrlResponse;
import it.unical.geosentinel.media.web.dto.MediaDtos.InitUploadRequest;
import it.unical.geosentinel.media.web.dto.MediaDtos.MediaResponse;
import it.unical.geosentinel.media.web.dto.MediaDtos.UploadSessionResponse;
import it.unical.geosentinel.media.web.dto.MediaMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media", description = "Endpoints for managing media uploads via MinIO/S3 presigned URLs")
public class MediaController {
    // Whitelist di ?sort=: un campo sconosciuto finirebbe nell'ORDER BY e diventerebbe un 500
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "sizeBytes");

    private final MediaService mediaService;
    private final MediaMapper mediaMapper;
    private final CurrentUser currentUser;

    public MediaController(MediaService mediaService, MediaMapper mediaMapper, CurrentUser currentUser) {
        this.mediaService = mediaService;
        this.mediaMapper = mediaMapper;
        this.currentUser = currentUser;
    }

    @PostMapping("/upload-requests")
    @Operation(summary = "Request an upload: returns a presigned POST policy (URL + form fields) for direct upload to storage", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UploadSessionResponse> initiateUpload(@Valid @RequestBody InitUploadRequest request) {
        UUID ownerId = currentUser.getRequiredUserId();
        UploadSessionView session = mediaService.initiateUpload(ownerId, mediaMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/media/{id}")
                .buildAndExpand(session.mediaId())
                .toUri();
        return ResponseEntity.created(location).body(mediaMapper.toResponse(session));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm upload completion after pushing to storage (size and content are verified)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MediaResponse> confirmUpload(@PathVariable UUID id) {
        UUID ownerId = currentUser.getRequiredUserId();
        MediaFileView file = mediaService.confirmUpload(ownerId, id);
        return ResponseEntity.ok(mediaMapper.toResponse(file));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get media file details by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MediaResponse> getMedia(@PathVariable UUID id) {
        MediaFileView media = mediaService.getMedia(currentUser.getRequiredUserId(), id);
        return ResponseEntity.ok(mediaMapper.toResponse(media));
    }

    @GetMapping("/{id}/download-url")
    @Operation(summary = "Get a temporary presigned GET URL for downloading media", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(@PathVariable UUID id) {
        String url = mediaService.getPresignedDownloadUrl(currentUser.getRequiredUserId(), id);
        return ResponseEntity.ok(new DownloadUrlResponse(id, url));
    }

    @GetMapping("/attached")
    @Operation(summary = "Get media attachments linked to a specific entity (TRAIL or HAZARD_REPORT)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<MediaResponse>> getAttachedMedia(
            @RequestParam MediaTargetType entityType,
            @RequestParam UUID entityId
    ) {
        List<MediaFileView> media = mediaService.getAttachedMedia(entityType, entityId);
        return ResponseEntity.ok(media.stream().map(mediaMapper::toResponse).toList());
    }

    @GetMapping("/my")
    @Operation(summary = "List current user's uploaded media", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<MediaResponse> getMyMedia(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        UUID ownerId = currentUser.getRequiredUserId();
        return new PagedModel<>(mediaService.getUserMedia(ownerId, pageable).map(mediaMapper::toResponse));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an uploaded media file and purge it from object storage (owner or admin)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteMedia(@PathVariable UUID id) {
        mediaService.deleteMedia(currentUser.getRequiredUserId(), currentUser.isAdmin(), id);
        return ResponseEntity.noContent().build();
    }

    static void checkSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTABLE_FIELDS);
            }
        }
    }
}
