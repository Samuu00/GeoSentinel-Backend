package it.unical.geosentinel.trail.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.trail.web.dto.TrailMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import it.unical.geosentinel.trail.application.TrailService;
import it.unical.geosentinel.trail.application.dto.TrailCommands.BoundingBox;
import it.unical.geosentinel.trail.application.dto.TrailCommands.GeoQuery;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailSummary;
import it.unical.geosentinel.trail.web.dto.TrailDtos.CreateTrailRequest;
import it.unical.geosentinel.trail.web.dto.TrailDtos.TrailDetailResponse;
import it.unical.geosentinel.trail.web.dto.TrailDtos.TrailSummaryResponse;
import it.unical.geosentinel.trail.web.dto.TrailDtos.UpdateTrailRequest;
import it.unical.geosentinel.trail.web.dto.TrailDtos.UpdateTrailStatusRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trails")
@Tag(name = "Trails", description = "Endpoints for trail exploration, geospatial discovery and management")
public class TrailController {
    private final TrailService trailService;
    private final TrailMapper trailMapper;

    public TrailController(TrailService trailService, TrailMapper trailMapper) {
        this.trailService = trailService;
        this.trailMapper = trailMapper;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get trail details by ID including GeoJSON path")
    public ResponseEntity<TrailDetailResponse> getById(@PathVariable UUID id) {
        TrailDetail detail = trailService.getTrail(id);
        return ResponseEntity.ok(trailMapper.toResponse(detail));
    }

    @GetMapping("/nearby")
    @Operation(summary = "Find trails within a given distance in meters from coordinates")
    public ResponseEntity<List<TrailSummaryResponse>> findNearby(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam(defaultValue = "10000.0") @Min(1) @Max(50000) double radius,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        GeoQuery query = new GeoQuery(lon, lat, radius, limit);
        List<TrailSummary> summaries = trailService.findNearby(query);
        return ResponseEntity.ok(summaries.stream().map(trailMapper::toResponse).toList());
    }

    @GetMapping
    @Operation(summary = "Find trails contained within a map bounding box")
    public ResponseEntity<List<TrailSummaryResponse>> findInBoundingBox(@RequestParam String bbox) {
        List<TrailSummary> summaries = trailService.findInBoundingBox(parseBoundingBox(bbox));
        return ResponseEntity.ok(summaries.stream().map(trailMapper::toResponse).toList());
    }

    // BoundingBox e parseDouble lanciano IllegalArgumentException, che senza handler diventerebbe un 500
    private static BoundingBox parseBoundingBox(String bbox) {
        String[] parts = bbox.split(",");
        try {
            if (parts.length != 4) {
                throw new IllegalArgumentException();
            }
            return new BoundingBox(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim())
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid bbox: expected minLon,minLat,maxLon,maxLat with min < max");
        }
    }

    @PostMapping
    @Operation(summary = "Create a new trail", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> create(@Valid @RequestBody CreateTrailRequest request) {
        TrailDetail created = trailService.create(trailMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(trailMapper.toResponse(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update trail details", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTrailRequest request
    ) {
        TrailDetail updated = trailService.update(id, trailMapper.toCommand(request));
        return ResponseEntity.ok(trailMapper.toResponse(updated));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change trail operational status", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTrailStatusRequest request
    ) {
        TrailDetail updated = trailService.changeStatus(id, request.status());
        return ResponseEntity.ok(trailMapper.toResponse(updated));
    }
}
