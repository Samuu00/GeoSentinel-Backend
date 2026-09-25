package it.unical.geosentinel.refuge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.refuge.web.dto.RefugeMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.AvailabilityView;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.RefugeView;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.AvailableResponse;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.CreateRefugeRequest;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.RefugeResponse;
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.UpdateCapacityRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/refuges")
@Tag(name = "Refuges", description = "Endpoints for discovering mountain refuges, checking bed availability, and management")
public class RefugeController {
    private final RefugeBookingService refugeBookingService;
    private final RefugeMapper refugeMapper;
    private final CurrentUser currentUser;

    public RefugeController(RefugeBookingService refugeBookingService, RefugeMapper refugeMapper, CurrentUser currentUser) {
        this.refugeBookingService = refugeBookingService;
        this.refugeMapper = refugeMapper;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get refuge details by ID")
    public ResponseEntity<RefugeResponse> getById(@PathVariable UUID id) {
        RefugeView view = refugeBookingService.getRefuge(id);
        return ResponseEntity.ok(refugeMapper.toResponse(view));
    }

    @GetMapping("/nearby")
    @Operation(summary = "Find refuges within a given distance in meters from coordinates")
    public ResponseEntity<List<RefugeResponse>> findNearby(
            @RequestParam("lon") @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam("lat") @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam(defaultValue = "15000.0") @Min(1) @Max(50000) double radius,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int limit
    ) {
        List<RefugeView> views = refugeBookingService.findNearby(lon, lat, radius, limit);
        return ResponseEntity.ok(views.stream().map(refugeMapper::toResponse).toList());
    }

    @GetMapping("/{id}/availability")
    @Operation(summary = "Check bed availability per night in a date range [from, to)")
    public ResponseEntity<AvailableResponse> checkAvailability(
            @PathVariable UUID id,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        AvailabilityView view = refugeBookingService.availability(id, from, to);
        return ResponseEntity.ok(refugeMapper.toResponse(view));
    }

    @PostMapping
    @Operation(summary = "Register a new refuge", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> create(@Valid @RequestBody CreateRefugeRequest request){
        RefugeView created = refugeBookingService.createRefuge(
                refugeMapper.toCommand(request, currentUser.getRequiredUserId()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(refugeMapper.toResponse(created));
    }

    @PatchMapping("/{id}/capacity")
    @Operation(summary = "Update refuge capacity", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> updateCapacity(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCapacityRequest request
    ) {
        RefugeView updated = refugeBookingService.updateCapacity(currentUser.getRequiredUserId(), currentUser.isAdmin(),
                id, request.capacity());
        return ResponseEntity.ok(refugeMapper.toResponse(updated));
    }
}
