package it.unical.geosentinel.trail.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.trail.web.dto.TrailMapper;
import jakarta.validation.Valid;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.trail.application.HazardReportService;
import it.unical.geosentinel.trail.application.dto.HazardCommands.HazardView;
import it.unical.geosentinel.trail.web.dto.HazardDtos.CreateHazardRequest;
import it.unical.geosentinel.trail.web.dto.HazardDtos.HazardResponse;
import it.unical.geosentinel.trail.web.dto.HazardDtos.UpdateHazardStatusRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hazards")
@Tag(name = "Hazard Reports", description = "Endpoints for environmental and trail hazards reporting and management")
public class HazardReportController {
    private final HazardReportService hazardReportService;
    private final TrailMapper trailMapper;
    private final CurrentUser currentUser;

    public HazardReportController(HazardReportService hazardReportService, TrailMapper trailMapper, CurrentUser currentUser) {
        this.hazardReportService = hazardReportService;
        this.trailMapper = trailMapper;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getHazard", summary = "Get hazard report by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HazardResponse> getById(@PathVariable UUID id) {
        HazardView view = hazardReportService.getHazard(id);
        return ResponseEntity.ok(trailMapper.toResponse(view));
    }

    @GetMapping("/trail/{trailId}")
    @Operation(operationId = "findActiveHazardsByTrail", summary = "List active hazards near a specific trail (buffer 100m)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<HazardResponse>> getActiveNearTrail(@PathVariable UUID trailId) {
        List<HazardView> views = hazardReportService.findNearTrail(trailId);
        return ResponseEntity.ok(views.stream().map(trailMapper::toResponse).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per la documentazione OpenAPI: a runtime vince lo status della ResponseEntity
    @Operation(operationId = "createHazard", summary = "Submit a hazard report", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HazardResponse> create(@Valid @RequestBody CreateHazardRequest request) {
        UUID reporterId = currentUser.getRequiredUserId();
        HazardView created = hazardReportService.create(reporterId, trailMapper.toCommand(request));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(trailMapper.toResponse(created));
    }

    @PatchMapping("/{id}/status")
    @Operation(operationId = "changeHazardStatus", summary = "Verify, reject or resolve a hazard report", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HazardResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateHazardStatusRequest request
    ) {
        HazardView updated = hazardReportService.changeStatus(id, request.status());
        return ResponseEntity.ok(trailMapper.toResponse(updated));
    }
}
