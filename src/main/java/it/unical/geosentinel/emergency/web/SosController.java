// Controller REST delle allerte SOS, esposto sotto /api/v1/sos.
// Raccoglie sia gli endpoint dell'escursionista (lancio, posizione, annullamento, allerta in corso) sia quelli
// degli operatori del soccorso (elenco, ricerca vicina, invio della squadra, chiusura). I ruoli vengono
// verificati in SosService.
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
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosAlertView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosRaiseResult;
import it.unical.geosentinel.emergency.web.dto.SosDtos.DispatchTeamRequest;
import it.unical.geosentinel.emergency.web.dto.SosDtos.RaiseSosRequest;
import it.unical.geosentinel.emergency.web.dto.SosDtos.SosAlertResponse;
import it.unical.geosentinel.emergency.web.dto.SosDtos.UpdateSosLocationRequest;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
@RequestMapping("/api/v1/sos")
@Tag(name = "SOS Alerts", description = "Endpoints for raising emergency alerts, tracking live hiker location, and dispatching rescue teams")
/**
 * Endpoint HTTP delle allerte SOS. Ricava l'utente dal token con {@link CurrentUser}, converte le richieste
 * con {@link SosMapper} e delega tutta la logica a {@link SosService}.
 */
public class SosController {
    /**
     * Campi su cui il client può ordinare l'elenco delle allerte. L'ordinamento richiesto finisce nell'ORDER BY,
     * quindi un campo sconosciuto produrrebbe un errore 500: per questo si accetta solo una lista chiusa.
     * status è escluso perché è salvato come stringa e l'ordine alfabetico non avrebbe senso.
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "updatedAt");

    private final SosService sosService;
    private final SosMapper sosMapper;
    private final CurrentUser currentUser;

    /**
     * @param sosService servizio delle emergenze
     * @param sosMapper convertitore tra DTO e comandi o viste
     * @param currentUser accesso all'utente autenticato
     */
    public SosController(SosService sosService, SosMapper sosMapper, CurrentUser currentUser) {
        this.sosService = sosService;
        this.sosMapper = sosMapper;
        this.currentUser = currentUser;
    }

    /**
     * Lancia un SOS. L'header Idempotency-Key è facoltativo, perché una richiesta d'aiuto senza chiave non va
     * mai rifiutata. Se l'allerta è nuova la risposta è 201 con l'header Location; se la richiesta è un retry o
     * l'utente ha già un'allerta aperta la risposta è 200 con l'allerta esistente.
     *
     * @param idempotencyKey chiave di idempotenza dell'azione dell'utente, può mancare
     * @param request posizione e note mediche
     * @return l'allerta creata o quella esistente
     */
    @PostMapping
    // L'annotazione serve solo a documentare il 201 in OpenAPI: a runtime lo stato lo decide la ResponseEntity.
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "raiseSos", summary = "Raise an SOS alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> raiseSos(
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody RaiseSosRequest request
    ) {
        UUID hikerId = currentUser.getRequiredUserId();
        SosRaiseResult result = sosService.raiseSos(hikerId, idempotencyKey, sosMapper.toCommand(request));
        SosAlertResponse body = sosMapper.toResponse(result.alert());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    /**
     * Aggiorna la posizione dell'allerta in corso; l'app la invia periodicamente.
     *
     * @param id id dell'allerta
     * @param request nuova posizione
     * @return l'allerta aggiornata
     */
    @PutMapping("/{id}/location")
    @Operation(operationId = "updateSosLocation", summary = "Update live location of an active SOS alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> updateLocation(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSosLocationRequest request
    ) {
        UUID hikerId = currentUser.getRequiredUserId();
        SosAlertView updated = sosService.updateLiveLocation(id, hikerId, sosMapper.toCommand(request));
        return ResponseEntity.ok(sosMapper.toResponse(updated));
    }

    /**
     * Restituisce l'allerta aperta dell'utente autenticato, oppure 404 se non ne ha.
     *
     * @return l'allerta in corso
     */
    @GetMapping("/active")
    @Operation(operationId = "getMyActiveSos", summary = "Get the current user's active SOS alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> getActiveSos(){
        UUID hikerId = currentUser.getRequiredUserId();
        SosAlertView alert = sosService.getActiveAlertForHiker(hikerId);
        return ResponseEntity.ok(sosMapper.toResponse(alert));
    }

    /**
     * Annulla l'allerta dell'utente autenticato e risponde 204.
     *
     * @param id id dell'allerta
     * @return risposta vuota
     */
    @DeleteMapping("/{id}")
    // L'annotazione serve solo a documentare il 204 in OpenAPI: a runtime lo stato lo decide la ResponseEntity.
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelSos", summary = "Cancel an active SOS alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> cancelSos(@PathVariable UUID id){
        UUID hikerId = currentUser.getRequiredUserId();
        sosService.cancelSos(id, hikerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Legge un'allerta per id: l'escursionista solo la propria, gli operatori tutte.
     *
     * @param id id dell'allerta
     * @return l'allerta
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getSosAlert", summary = "Get SOS alert details by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> getById(@PathVariable UUID id){
        SosAlertView alert = sosService.getAlert(id);
        return ResponseEntity.ok(sosMapper.toResponse(alert));
    }

    /**
     * Elenco paginato delle allerte aperte, per gli operatori. Di base 20 per pagina, dalla più vecchia.
     *
     * @param pageable pagina e ordinamento; l'ordinamento è limitato a {@link #SORTABLE_FIELDS}
     * @return la pagina di allerte
     */
    @GetMapping
    @Operation(operationId = "findActiveSosAlerts", summary = "Find active/dispatched SOS alerts (Rescue Operators only)", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<SosAlertResponse> findActiveAlerts(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        return new PagedModel<>(sosService.findActiveAlerts(pageable).map(sosMapper::toResponse));
    }

    /**
     * Allerte aperte vicino a un punto, ordinate per distanza, per gli operatori.
     *
     * @param lon longitudine, tra -180 e 180
     * @param lat latitudine, tra -90 e 90
     * @param radius raggio in metri (predefinito 25 km, massimo 100 km)
     * @param limit numero massimo di allerte (predefinito 20, massimo 50)
     * @return l'elenco delle allerte
     */
    @GetMapping("/nearby")
    @Operation(operationId = "findSosAlertsNearby", summary = "Find active SOS alerts within radius in meters (Rescue Operators only)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<SosAlertResponse>> findNearby(
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam(defaultValue = "25000.0") @Min(1) @Max(100000) double radius,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        List<SosAlertView> alerts = sosService.findActiveNearby(lon, lat, radius, limit);
        return ResponseEntity.ok(alerts.stream().map(sosMapper::toResponse).toList());
    }

    /**
     * Invia una squadra sull'allerta. Se un altro operatore l'ha già presa in carico la risposta è 409
     * alert-already-taken.
     *
     * @param id id dell'allerta
     * @param request squadra da inviare
     * @return l'allerta aggiornata
     */
    @PatchMapping("/{id}/dispatch")
    @Operation(operationId = "dispatchRescueTeam", summary = "Dispatch a rescue team to the SOS alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> dispatchTeam(
            @PathVariable UUID id,
            @Valid @RequestBody DispatchTeamRequest request
    ) {
        UUID operatorId = currentUser.getRequiredUserId();
        SosAlertView dispatched = sosService.dispatchTeam(operatorId, id, sosMapper.toCommand(request));
        return ResponseEntity.ok(sosMapper.toResponse(dispatched));
    }

    /**
     * Chiude l'allerta come risolta e libera la squadra.
     *
     * @param id id dell'allerta
     * @return l'allerta chiusa
     */
    @PatchMapping("/{id}/resolve")
    @Operation(operationId = "resolveSosAlert", summary = "Mark an SOS alert as resolved and release the rescue team", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SosAlertResponse> resolve(@PathVariable UUID id){
        SosAlertView resolved = sosService.resolveAlert(id);
        return ResponseEntity.ok(sosMapper.toResponse(resolved));
    }

    /**
     * Controlla che l'ordinamento richiesto usi solo i campi ammessi.
     *
     * @param sort ordinamento ricevuto dal client
     * @throws ResponseStatusException con stato 400 se c'è un campo non ammesso
     */
    static void checkSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTABLE_FIELDS);
            }
        }
    }
}
