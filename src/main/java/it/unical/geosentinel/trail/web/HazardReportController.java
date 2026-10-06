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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
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

    // Le segnalazioni dell'utente corrente, in ogni stato, dalla più recente. "/mine" vince su "/{id}" (path letterale)
    @GetMapping("/mine")
    @Operation(operationId = "findMyHazards", summary = "List current user's hazard reports, newest first", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<HazardResponse> getMyHazardReports(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        UUID reporterId = currentUser.getRequiredUserId();
        return new PagedModel<>(hazardReportService.findMine(reporterId, pageable).map(trailMapper::toResponse));
    }

    // I pericoli attivi (VERIFIED) intorno a un punto, di tutti gli utenti, dal più vicino. Lista come /pois/nearby:
    // niente Pageable (con una query nativa un ?sort= arriverebbe nell'SQL). "/nearby" vince su "/{id}" (path letterale)
    @GetMapping("/nearby")
    @Operation(operationId = "findHazardsNearby", summary = "List active (verified) hazards near coordinates, nearest first", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<HazardResponse>> findNearby(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam(defaultValue = "5000.0") @Min(1) @Max(50000) double radius,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int limit
    ) {
        List<HazardView> views = hazardReportService.findNearby(lon, lat, radius, limit);
        return ResponseEntity.ok(views.stream().map(trailMapper::toResponse).toList());
    }

    @GetMapping("/trail/{trailId}")
    @Operation(operationId = "findActiveHazardsByTrail",
            summary = "List active hazards near a specific trail (buffer 100m), newest first, at most 50",
            description = "Verified hazards of all users; submitted ones only of the caller (all of them for a park manager)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<HazardResponse>> getActiveNearTrail(@PathVariable UUID trailId) {
        List<HazardView> views = hazardReportService.findNearTrail(
                trailId, currentUser.getRequiredUserId(), currentUser.isParkManager());
        return ResponseEntity.ok(views.stream().map(trailMapper::toResponse).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per OpenAPI, a runtime decide la ResponseEntity
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

    // Whitelist del sort, come nelle altre liste: un campo qualsiasi darebbe un 500 (proprietà inesistente)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt");

    static void checkSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTABLE_FIELDS);
            }
        }
    }
}
