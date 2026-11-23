// Controller REST dei ticket di manutenzione, esposto sotto /api/v1/maintenance-tickets.
// Oltre a tradurre richieste e risposte, gestisce l'ETag e l'header If-Match con cui i client fanno il
// controllo di concorrenza ottimistico sulle modifiche.
package it.unical.geosentinel.maintenance.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.maintenance.application.MaintenanceService;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketQuery;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.maintenance.domain.TicketPriority;
import it.unical.geosentinel.maintenance.domain.TicketStatus;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.AssignTicketRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.CreateTicketRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.TicketResponse;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.UpdateTicketRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceDtos.UpdateTicketStatusRequest;
import it.unical.geosentinel.maintenance.web.dto.MaintenanceMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Endpoint dei ticket di manutenzione, riservati ai gestori del parco.
 * <p>
 * Il percorso non sta sotto /trails: lì le GET sono pubbliche, e un utente anonimo riceverebbe 403 invece
 * di 401. Ogni risposta con un ticket porta l'header ETag con la sua versione; le modifiche richiedono
 * l'header If-Match con quella versione (428 se manca, 409 se nel frattempo il ticket è cambiato).
 */
@RestController
@RequestMapping("/api/v1/maintenance-tickets")
@Tag(name = "Maintenance", description = "Endpoints for managing maintenance tickets on trails, refuges and hazards")
public class MaintenanceController {
    /**
     * Campi su cui il client può ordinare la ricerca. priority è escluso perché l'enum è salvato come stringa
     * e verrebbe ordinato alfabeticamente (CRITICAL, HIGH, LOW, MEDIUM) invece che per gravità.
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "updatedAt", "status", "title");

    private final MaintenanceService maintenanceService;
    private final MaintenanceMapper maintenanceMapper;

    /**
     * @param maintenanceService servizio della manutenzione
     * @param maintenanceMapper convertitore tra DTO e comandi o viste
     */
    public MaintenanceController(MaintenanceService maintenanceService, MaintenanceMapper maintenanceMapper) {
        this.maintenanceService = maintenanceService;
        this.maintenanceMapper = maintenanceMapper;
    }

    /**
     * Legge un ticket; la risposta porta l'ETag con la versione.
     *
     * @param id id del ticket
     * @return il ticket
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getTicket", summary = "Get maintenance ticket by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> getById(@PathVariable UUID id) {
        return withETag(ResponseEntity.ok(), maintenanceService.getTicket(id));
    }

    /**
     * Ricerca paginata dei ticket con filtri facoltativi. Di base 20 per pagina, dal più recente.
     *
     * @param status filtro sullo stato
     * @param priority filtro sulla priorità
     * @param assignedTo filtro sull'assegnatario
     * @param pageable pagina e ordinamento, limitato a {@link #SORTABLE_FIELDS}
     * @return la pagina di ticket
     */
    @GetMapping
    @Operation(operationId = "searchTickets", summary = "Search maintenance tickets with pagination and filtering", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<TicketResponse> searchTickets(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) UUID assignedTo,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        TicketQuery query = new TicketQuery(status, priority, assignedTo);
        return new PagedModel<>(maintenanceService.findTickets(query, pageable).map(maintenanceMapper::toResponse));
    }

    /**
     * Ticket di un sentiero, letti dalla cache quando possibile.
     *
     * @param trailId id del sentiero
     * @return i ticket del sentiero
     */
    @GetMapping("/trail/{trailId}")
    @Operation(operationId = "findTicketsByTrail", summary = "List maintenance tickets of a trail (cached)", security = @SecurityRequirement(name = "bearerAuth"))
    public List<TicketResponse> byTrail(@PathVariable UUID trailId) {
        return maintenanceMapper.toResponses(maintenanceService.byTrail(trailId));
    }

    /**
     * Apre un ticket e risponde 201 con gli header Location ed ETag.
     *
     * @param request dati del ticket
     * @return il ticket creato
     */
    @PostMapping
    // L'annotazione serve solo a documentare il 201 in OpenAPI: a runtime lo stato lo decide la ResponseEntity.
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createTicket", summary = "Open a new maintenance ticket", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketView created = maintenanceService.createTicket(maintenanceMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return withETag(ResponseEntity.created(location), created);
    }

    /**
     * Modifica titolo, descrizione e priorità.
     *
     * @param id id del ticket
     * @param ifMatch versione letta dal client
     * @param request nuovi dati
     * @return il ticket aggiornato, con il nuovo ETag
     */
    @PutMapping("/{id}")
    @Operation(operationId = "updateTicket", summary = "Update ticket details (title, description, priority)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> update(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateTicketRequest request
    ) {
        TicketView updated = maintenanceService.updateDetails(id, parseVersion(ifMatch), maintenanceMapper.toCommand(request));
        return withETag(ResponseEntity.ok(), updated);
    }

    /**
     * Assegna il ticket a un utente.
     *
     * @param id id del ticket
     * @param ifMatch versione letta dal client
     * @param request utente a cui assegnare il ticket
     * @return il ticket aggiornato, con il nuovo ETag
     */
    @PatchMapping("/{id}/assign")
    @Operation(operationId = "assignTicket", summary = "Assign a ticket to an operator", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> assign(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request
    ) {
        TicketView assigned = maintenanceService.assignTicket(id, parseVersion(ifMatch), request.operatorId());
        return withETag(ResponseEntity.ok(), assigned);
    }

    /**
     * Cambia lo stato del ticket.
     *
     * @param id id del ticket
     * @param ifMatch versione letta dal client
     * @param request nuovo stato
     * @return il ticket aggiornato, con il nuovo ETag
     */
    @PatchMapping("/{id}/status")
    @Operation(operationId = "changeTicketStatus", summary = "Update ticket status (IN_PROGRESS, RESOLVED, CANCELLED)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> changeStatus(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateTicketStatusRequest request
    ) {
        TicketView updated = maintenanceService.changeStatus(id, parseVersion(ifMatch), request.status());
        return withETag(ResponseEntity.ok(), updated);
    }

    /**
     * Completa la risposta con il corpo del ticket e con l'ETag, cioè la versione tra virgolette.
     *
     * @param builder risposta già impostata con lo stato
     * @param view ticket da restituire
     * @return la risposta completa
     */
    private ResponseEntity<TicketResponse> withETag(ResponseEntity.BodyBuilder builder, TicketView view) {
        return builder.eTag("\"" + view.version() + "\"").body(maintenanceMapper.toResponse(view));
    }

    /**
     * Controlla il parametro sort ricevuto dal client. Il valore finisce nell'ORDER BY della query, e un campo
     * sconosciuto provocherebbe un errore di Hibernate (500): per questo si accettano solo i campi elencati,
     * senza percorsi annidati come trail.name.
     *
     * @param sort ordinamento richiesto
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

    /**
     * Estrae la versione dall'header If-Match. Accetta la forma standard tra virgolette, il numero senza
     * virgolette e l'ETag debole con il prefisso W/.
     *
     * @param ifMatch valore dell'header
     * @return la versione numerica
     * @throws ResponseStatusException con stato 400 se il valore non contiene un numero
     */
    static long parseVersion(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain the ticket version, e.g. \"3\"");
        }
    }
}
