package it.unical.geosentinel.emergency.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.emergency.web.dto.SosMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import it.unical.geosentinel.emergency.application.SosService;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RescueTeamView;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.CreateRescueTeamRequest;
import it.unical.geosentinel.emergency.web.dto.RescueTeamDtos.RescueTeamResponse;
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
@RequestMapping("/api/v1/rescue-teams")
@Tag(name = "Rescue Teams", description = "Endpoints for managing rescue teams and discovering available units")
public class RescueTeamController {
    private final SosService sosService;
    private final SosMapper sosMapper;

    public RescueTeamController(SosService sosService, SosMapper sosMapper) {
        this.sosService = sosService;
        this.sosMapper = sosMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per la documentazione OpenAPI: a runtime vince lo status della ResponseEntity
    @Operation(summary = "Register a new rescue team unit", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RescueTeamResponse> createTeam(@Valid @RequestBody CreateRescueTeamRequest request){
        RescueTeamView created = sosService.createTeam(sosMapper.toCommand(request));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(sosMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a rescue team by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RescueTeamResponse> getTeam(@PathVariable UUID id) {
        return ResponseEntity.ok(sosMapper.toResponse(sosService.getTeam(id)));
    }

    @GetMapping("/available-nearby")
    @Operation(summary = "Find available rescue teams ordered by geodesic distance from coordinates", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<RescueTeamResponse>> findAvailableNearby(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam(defaultValue = "50000.0") @Min(1) @Max(150000) double radius,
            @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit
    ) {
        List<RescueTeamView> teams = sosService.findAvailableTeamsNearby(lon, lat, radius, limit);
        return ResponseEntity.ok(teams.stream().map(sosMapper::toResponse).toList());
    }
}
