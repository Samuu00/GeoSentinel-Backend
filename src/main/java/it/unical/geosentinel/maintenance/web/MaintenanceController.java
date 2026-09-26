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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Non sotto /trails/**: lì le GET sono permitAll e un anonimo riceverebbe 403 invece di 401.
// Le modifiche richiedono If-Match con la version dell'ETag (428 se manca, 409 se vecchia).
@RestController
@RequestMapping("/api/v1/maintenance-tickets")
@Tag(name = "Maintenance", description = "Endpoints for managing maintenance tickets on trails, refuges and hazards")
public class MaintenanceController {
    // niente priority: l'enum è salvato come stringa e si ordinerebbe alfabeticamente (CRITICAL, HIGH, LOW, MEDIUM)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "updatedAt", "status", "title");

    private final MaintenanceService maintenanceService;
    private final MaintenanceMapper maintenanceMapper;

    public MaintenanceController(MaintenanceService maintenanceService, MaintenanceMapper maintenanceMapper) {
        this.maintenanceService = maintenanceService;
        this.maintenanceMapper = maintenanceMapper;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get maintenance ticket by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> getById(@PathVariable UUID id) {
        return withETag(ResponseEntity.ok(), maintenanceService.getTicket(id));
    }

    @GetMapping
    @Operation(summary = "Search maintenance tickets with pagination and filtering", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<TicketResponse> searchTickets(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) UUID assignedTo,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        TicketQuery query = new TicketQuery(status, priority, assignedTo);
        return new PagedModel<>(maintenanceService.findTickets(query, pageable).map(maintenanceMapper::toResponse));
    }

    @GetMapping("/trail/{trailId}")
    @Operation(summary = "List maintenance tickets of a trail (cached)", security = @SecurityRequirement(name = "bearerAuth"))
    public List<TicketResponse> byTrail(@PathVariable UUID trailId) {
        return maintenanceMapper.toResponses(maintenanceService.byTrail(trailId));
    }

    @PostMapping
    @Operation(summary = "Open a new maintenance ticket", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketView created = maintenanceService.createTicket(maintenanceMapper.toCommand(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return withETag(ResponseEntity.created(location), created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update ticket details (title, description, priority)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> update(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateTicketRequest request
    ) {
        TicketView updated = maintenanceService.updateDetails(id, parseVersion(ifMatch), maintenanceMapper.toCommand(request));
        return withETag(ResponseEntity.ok(), updated);
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "Assign a ticket to an operator", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> assign(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody AssignTicketRequest request
    ) {
        TicketView assigned = maintenanceService.assignTicket(id, parseVersion(ifMatch), request.operatorId());
        return withETag(ResponseEntity.ok(), assigned);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update ticket status (IN_PROGRESS, RESOLVED, CANCELLED)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TicketResponse> changeStatus(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateTicketStatusRequest request
    ) {
        TicketView updated = maintenanceService.changeStatus(id, parseVersion(ifMatch), request.status());
        return withETag(ResponseEntity.ok(), updated);
    }

    private ResponseEntity<TicketResponse> withETag(ResponseEntity.BodyBuilder builder, TicketView view) {
        return builder.eTag("\"" + view.version() + "\"").body(maintenanceMapper.toResponse(view));
    }

    // ?sort= arriva dal client e finisce nell'ORDER BY della @Query: un campo sconosciuto darebbe un errore
    // di Hibernate (500). Solo campi espliciti, niente percorsi annidati come trail.name
    static void checkSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTABLE_FIELDS);
            }
        }
    }

    // accetta "3", 3 e W/"3"; qualunque altra cosa è 400
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
