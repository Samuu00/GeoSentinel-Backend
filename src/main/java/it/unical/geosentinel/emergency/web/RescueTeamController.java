// Controller REST delle squadre di soccorso, esposto sotto /api/v1/rescue-teams.
// Converte le richieste HTTP in comandi per SosService e i risultati in DTO di risposta; i controlli sui
// ruoli stanno nel servizio.
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
/**
 * Endpoint per creare una squadra (solo amministratori), leggerne una e cercare le squadre disponibili
 * vicino a un punto (operatori e amministratori).
 */
public class RescueTeamController {
    private final SosService sosService;
    private final SosMapper sosMapper;

    /**
     * @param sosService servizio delle emergenze
     * @param sosMapper convertitore tra DTO dell'API e comandi o viste del servizio
     */
    public RescueTeamController(SosService sosService, SosMapper sosMapper) {
        this.sosService = sosService;
        this.sosMapper = sosMapper;
    }

    /**
     * Crea una squadra e risponde 201 con l'header Location che punta alla nuova risorsa.
     *
     * @param request dati della squadra, validati
     * @return la squadra creata
     */
    @PostMapping
    // L'annotazione serve solo a documentare il 201 in OpenAPI: a runtime lo stato lo decide la ResponseEntity.
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createRescueTeam", summary = "Register a new rescue team unit", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RescueTeamResponse> createTeam(@Valid @RequestBody CreateRescueTeamRequest request){
        RescueTeamView created = sosService.createTeam(sosMapper.toCommand(request));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(sosMapper.toResponse(created));
    }

    /**
     * Legge una squadra per id.
     *
     * @param id id della squadra
     * @return la squadra, oppure 404 se non esiste
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getRescueTeam", summary = "Get a rescue team by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RescueTeamResponse> getTeam(@PathVariable UUID id) {
        return ResponseEntity.ok(sosMapper.toResponse(sosService.getTeam(id)));
    }

    /**
     * Cerca le squadre disponibili vicino a un punto, ordinate per distanza geodetica.
     *
     * @param lon longitudine, tra -180 e 180
     * @param lat latitudine, tra -90 e 90
     * @param radius raggio di ricerca in metri (predefinito 50 km, massimo 150 km)
     * @param limit numero massimo di squadre (predefinito 10, massimo 30)
     * @return l'elenco delle squadre
     */
    @GetMapping("/available-nearby")
    @Operation(operationId = "findAvailableRescueTeamsNearby", summary = "Find available rescue teams ordered by geodesic distance from coordinates", security = @SecurityRequirement(name = "bearerAuth"))
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
