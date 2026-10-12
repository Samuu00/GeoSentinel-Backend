package it.unical.geosentinel.trail.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.trail.domain.PoiType;
import it.unical.geosentinel.trail.web.dto.TrailMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import it.unical.geosentinel.trail.application.PoiService;
import it.unical.geosentinel.trail.application.dto.PoiCommands.PoiView;
import it.unical.geosentinel.trail.web.dto.PoiDtos.CreatePoiRequest;
import it.unical.geosentinel.trail.web.dto.PoiDtos.PoiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pois")
@Tag(name = "Points of Interest", description = "Endpoints for finding and registering points of interest")
public class PoiController {
    private final PoiService poiService;
    private final TrailMapper trailMapper;

    public PoiController(PoiService poiService, TrailMapper trailMapper) {
        this.poiService = poiService;
        this.trailMapper = trailMapper;
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getPoi", summary = "Get POI by ID")
    public ResponseEntity<PoiResponse> getById(@PathVariable UUID id) {
        PoiView view = poiService.getPoi(id);
        return ResponseEntity.ok(trailMapper.toResponse(view));
    }

    @GetMapping("/nearby")
    @Operation(operationId = "findPoisNearby", summary = "Find POIs nearby coordinates with optional type filter")
    public ResponseEntity<List<PoiResponse>> findNearby(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam(defaultValue = "5000.0") @Min(1) @Max(50000) double radius,
            @RequestParam(required = false) PoiType type,
            @RequestParam(defaultValue = "30") @Min(1) @Max(300) int limit
    ) {
        List<PoiView> views = poiService.findNearby(lon, lat, radius, type, limit);
        return ResponseEntity.ok(views.stream().map(trailMapper::toResponse).toList());
    }

    @GetMapping("/trail/{trailId}")
    @Operation(operationId = "findPoisByTrail", summary = "List all POIs associated with a specific trail")
    public ResponseEntity<List<PoiResponse>> findByTrail(@PathVariable UUID trailId) {
        List<PoiView> views = poiService.findByTrail(trailId);
        return ResponseEntity.ok(views.stream().map(trailMapper::toResponse).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per OpenAPI, a runtime decide la ResponseEntity
    @Operation(operationId = "createPoi", summary = "Create a new POI", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PoiResponse> create(@Valid @RequestBody CreatePoiRequest request) {
        PoiView created = poiService.create(trailMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(trailMapper.toResponse(created));
    }
}
