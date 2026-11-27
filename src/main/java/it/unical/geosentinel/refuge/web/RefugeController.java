// Controller REST dei rifugi, sotto /api/v1/refuges: dettaglio, ricerca attorno a un punto,
// disponibilità dei posti per notte, creazione e modifica dei posti letto. Tutte le rotte richiedono il
// token; creazione e modifica sono riservate ai gestori, con i controlli nel service.
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
import it.unical.geosentinel.refuge.web.dto.RefugeDtos.UpdateRefugePriceRequest;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Espone le operazioni sui rifugi e converte richieste e risposte con {@link RefugeMapper}.
 */
@RestController
@RequestMapping("/api/v1/refuges")
@Tag(name = "Refuges", description = "Endpoints for discovering mountain refuges, checking bed availability, and management")
public class RefugeController {
    private final RefugeBookingService refugeBookingService;
    private final RefugeMapper refugeMapper;
    private final CurrentUser currentUser;

    /**
     * @param refugeBookingService service dei rifugi e delle prenotazioni
     * @param refugeMapper         mapper tra DTO REST e oggetti del service
     * @param currentUser          accesso all'utente autenticato
     */
    public RefugeController(RefugeBookingService refugeBookingService, RefugeMapper refugeMapper, CurrentUser currentUser) {
        this.refugeBookingService = refugeBookingService;
        this.refugeMapper = refugeMapper;
        this.currentUser = currentUser;
    }

    /**
     * @param id identificativo del rifugio
     * @return 200 con il rifugio, 404 se non esiste
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getRefuge", summary = "Get refuge details by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> getById(@PathVariable UUID id) {
        RefugeView view = refugeBookingService.getRefuge(id);
        return ResponseEntity.ok(refugeMapper.toResponse(view));
    }

    /**
     * Cerca i rifugi attorno a un punto, dal più vicino.
     *
     * @param lon    longitudine del centro
     * @param lat    latitudine del centro
     * @param radius raggio in metri, di default 15 km, al massimo 50 km
     * @param limit  numero massimo di risultati, di default 30, al massimo 50
     * @return 200 con i rifugi
     */
    @GetMapping("/nearby")
    @Operation(operationId = "findRefugesNearby", summary = "Find refuges within a given distance in meters from coordinates", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<RefugeResponse>> findNearby(
            @RequestParam("lon") @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam("lat") @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam(defaultValue = "15000.0") @Min(1) @Max(50000) double radius,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int limit
    ) {
        List<RefugeView> views = refugeBookingService.findNearby(lon, lat, radius, limit);
        return ResponseEntity.ok(views.stream().map(refugeMapper::toResponse).toList());
    }

    /**
     * Restituisce i posti liberi per ogni notte dell'intervallo, dal giorno from incluso al giorno to
     * escluso.
     *
     * @param id   identificativo del rifugio
     * @param from prima notte, in formato ISO (aaaa-mm-gg)
     * @param to     giorno di fine, escluso
     * @param guests ospiti su cui calcolare il totale, di default 1, al massimo 20 come una prenotazione
     * @return 200 con la disponibilità e il totale; 422 se l'intervallo non è valido
     */
    @GetMapping("/{id}/availability")
    @Operation(operationId = "getRefugeAvailability", summary = "Check bed availability per night in a date range [from, to)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AvailableResponse> checkAvailability(
            @PathVariable UUID id,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(20) int guests
    ) {
        AvailabilityView view = refugeBookingService.availability(id, from, to, guests);
        return ResponseEntity.ok(refugeMapper.toResponse(view));
    }

    /**
     * Crea un rifugio. Il gestore è l'utente che fa la richiesta, ricavato dal JWT e non dal body.
     *
     * @param request dati del rifugio, validati
     * @return 201 con il rifugio creato
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per la documentazione OpenAPI: a runtime lo status lo decide la ResponseEntity
    @Operation(operationId = "createRefuge", summary = "Register a new refuge", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> create(@Valid @RequestBody CreateRefugeRequest request){
        RefugeView created = refugeBookingService.createRefuge(
                refugeMapper.toCommand(request, currentUser.getRequiredUserId()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(refugeMapper.toResponse(created));
    }

    /**
     * Cambia i posti letto di un rifugio; è permesso al gestore di quel rifugio o a un amministratore.
     *
     * @param id      identificativo del rifugio
     * @param request nuova capacità
     * @return 200 con il rifugio aggiornato; 403 se l'utente non può modificarlo; 409 se la capacità è
     *         inferiore agli ospiti già confermati
     */
    @PatchMapping("/{id}/capacity")
    @Operation(operationId = "updateRefugeCapacity", summary = "Update refuge capacity", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> updateCapacity(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCapacityRequest request
    ) {
        RefugeView updated = refugeBookingService.updateCapacity(currentUser.getRequiredUserId(), currentUser.isAdmin(),
                id, request.capacity());
        return ResponseEntity.ok(refugeMapper.toResponse(updated));
    }

    /**
     * Imposta o toglie il prezzo a persona per notte; è permesso al gestore di quel rifugio o a un
     * amministratore. Le prenotazioni già fatte conservano il totale di allora.
     *
     * @param id      identificativo del rifugio
     * @param request nuovo prezzo in centesimi, {@code null} per toglierlo
     * @return 200 con il rifugio aggiornato; 400 se il prezzo è fuori dai limiti; 403 se l'utente non può
     *         modificarlo
     */
    @PatchMapping("/{id}/price")
    @Operation(operationId = "updateRefugePrice", summary = "Set or clear the per-person nightly price of a refuge (euro cents)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RefugeResponse> updatePrice(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRefugePriceRequest request
    ) {
        RefugeView updated = refugeBookingService.updatePrice(currentUser.getRequiredUserId(), currentUser.isAdmin(),
                id, request.pricePerNightCents());
        return ResponseEntity.ok(refugeMapper.toResponse(updated));
    }
}
