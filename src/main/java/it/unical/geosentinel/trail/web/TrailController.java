// Controller REST dei sentieri, sotto /api/v1/trails: dettaglio, ricerca attorno a un punto, sentieri
// nel riquadro della mappa, percorso tra due punti e operazioni di gestione. Le letture sono pubbliche, le
// scritture richiedono il token e il ruolo di gestore, controllato nel service.
package it.unical.geosentinel.trail.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.trail.application.RouteService;
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
import it.unical.geosentinel.trail.web.dto.TrailDtos.RouteResponse;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Espone le operazioni sui sentieri e converte richieste e risposte con {@link TrailMapper}.
 */
@RestController
@RequestMapping("/api/v1/trails")
@Tag(name = "Trails", description = "Endpoints for trail exploration, geospatial discovery and management")
public class TrailController {
    private final TrailService trailService;
    private final TrailMapper trailMapper;
    private final RouteService routeService;

    /**
     * @param trailService service dei sentieri
     * @param trailMapper  mapper tra DTO REST e oggetti del service
     * @param routeService service del calcolo dei percorsi
     */
    public TrailController(TrailService trailService, TrailMapper trailMapper, RouteService routeService) {
        this.trailService = trailService;
        this.trailMapper = trailMapper;
        this.routeService = routeService;
    }

    /**
     * @param id identificativo del sentiero
     * @return 200 con il dettaglio e il tracciato completo, 404 se non esiste
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getTrail", summary = "Get trail details by ID including GeoJSON path")
    public ResponseEntity<TrailDetailResponse> getById(@PathVariable UUID id) {
        TrailDetail detail = trailService.getTrail(id);
        return ResponseEntity.ok(trailMapper.toResponse(detail));
    }

    /**
     * Cerca i sentieri attorno a un punto. I vincoli sui parametri rifiutano con un 400 coordinate fuori
     * range, un raggio oltre 50 km e più di 50 risultati.
     *
     * @param lon    longitudine del centro
     * @param lat    latitudine del centro
     * @param radius raggio in metri, di default 10 km
     * @param limit  numero massimo di risultati, di default 20
     * @return 200 con i riepiloghi dei sentieri, dal più vicino
     */
    @GetMapping("/nearby")
    @Operation(operationId = "findTrailsNearby", summary = "Find trails within a given distance in meters from coordinates")
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

    /**
     * Calcola il percorso a piedi tra due punti sui sentieri del parco. La rotta è pubblica come il resto
     * del catalogo. Non c'è ambiguità con "/{id}": Spring preferisce il segmento fisso "/route" alla
     * variabile di percorso.
     *
     * @param fromLon longitudine della partenza
     * @param fromLat latitudine della partenza
     * @param toLon   longitudine dell'arrivo
     * @param toLat   latitudine dell'arrivo
     * @return 200 con il percorso; 422 se non esiste; 503 se il motore non risponde
     */
    @GetMapping("/route")
    @Operation(operationId = "findRoute", summary = "Find a walking route between two points on the park trails only (no roads), avoiding closed trails; trailIds are the trails it follows",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Route found"),
                    @ApiResponse(responseCode = "422", description = "A point is more than 1 km from every park trail, or no chain of open trails connects the two points (no-route)"),
                    @ApiResponse(responseCode = "503", description = "The routing service is unavailable (routing-unavailable): retry after Retry-After")
            })
    public ResponseEntity<RouteResponse> findRoute(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double fromLon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double fromLat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double toLon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double toLat
    ) {
        RouteService.Route route = routeService.findRoute(fromLon, fromLat, toLon, toLat);
        return ResponseEntity.ok(new RouteResponse(route.path(), route.lengthM(), route.trailIds()));
    }

    /**
     * Restituisce i sentieri che attraversano il riquadro visibile della mappa.
     *
     * @param bbox riquadro nel formato {@code minLon,minLat,maxLon,maxLat}
     * @return 200 con i riepiloghi dei sentieri, al massimo 500
     * @throws ResponseStatusException 400 se il riquadro non è valido
     */
    @GetMapping
    @Operation(operationId = "findTrailsInBoundingBox", summary = "Find trails that cross a map bounding box (bbox=minLon,minLat,maxLon,maxLat), at most 500")
    public ResponseEntity<List<TrailSummaryResponse>> findInBoundingBox(@RequestParam String bbox) {
        List<TrailSummary> summaries = trailService.findInBoundingBox(parseBoundingBox(bbox));
        return ResponseEntity.ok(summaries.stream().map(trailMapper::toResponse).toList());
    }

    /**
     * Interpreta il parametro bbox. Sia il costruttore di BoundingBox sia {@code Double.parseDouble}
     * (con NumberFormatException, sua sottoclasse) lanciano IllegalArgumentException, che senza un handler
     * dedicato diventerebbe un 500: qui viene trasformata in un 400.
     *
     * @param bbox stringa con quattro numeri separati da virgole
     * @return il riquadro validato
     * @throws ResponseStatusException 400 se il formato o i valori non sono validi
     */
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

    /**
     * Crea un sentiero e restituisce nell'header Location l'indirizzo della nuova risorsa.
     *
     * @param request dati del sentiero, validati
     * @return 201 con il dettaglio del sentiero creato
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per la documentazione OpenAPI: a runtime lo status lo decide la ResponseEntity
    @Operation(operationId = "createTrail", summary = "Create a new trail", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> create(@Valid @RequestBody CreateTrailRequest request) {
        TrailDetail created = trailService.create(trailMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(trailMapper.toResponse(created));
    }

    /**
     * @param id      identificativo del sentiero
     * @param request nuovi dati, validati
     * @return 200 con il dettaglio aggiornato
     */
    @PutMapping("/{id}")
    @Operation(operationId = "updateTrail", summary = "Update trail details", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTrailRequest request
    ) {
        TrailDetail updated = trailService.update(id, trailMapper.toCommand(request));
        return ResponseEntity.ok(trailMapper.toResponse(updated));
    }

    /**
     * @param id      identificativo del sentiero
     * @param request nuovo stato
     * @return 200 con il dettaglio aggiornato; 422 se la transizione non è ammessa
     */
    @PatchMapping("/{id}/status")
    @Operation(operationId = "changeTrailStatus", summary = "Change trail operational status", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TrailDetailResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTrailStatusRequest request
    ) {
        TrailDetail updated = trailService.changeStatus(id, request.status());
        return ResponseEntity.ok(trailMapper.toResponse(updated));
    }
}
