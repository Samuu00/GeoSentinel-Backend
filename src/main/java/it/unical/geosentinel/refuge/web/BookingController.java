package it.unical.geosentinel.refuge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.refuge.web.dto.RefugeMapper;
import jakarta.validation.Valid;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingResult;
import it.unical.geosentinel.refuge.web.dto.BookingDtos.BookingResponse;
import it.unical.geosentinel.refuge.web.dto.BookingDtos.CreateBookingRequest;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Endpoints for managing refuge bed reservations")
public class BookingController {
    // status escluso: l'enum è salvato come stringa e si ordinerebbe in ordine alfabetico
    private static final Set<String> SORTABLE_FIELDS = Set.of("checkIn", "checkOut", "createdAt");

    private final RefugeBookingService refugeBookingService;
    private final CurrentUser currentUser;
    private final RefugeMapper refugeMapper;

    public BookingController(RefugeBookingService refugeBookingService, CurrentUser currentUser, RefugeMapper refugeMapper) {
        this.refugeBookingService = refugeBookingService;
        this.currentUser = currentUser;
        this.refugeMapper = refugeMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per OpenAPI, a runtime decide la ResponseEntity
    @Operation(
            operationId = "createBooking",
            summary = "Book beds in a refuge",
            security = @SecurityRequirement(name = "bearerAuth"),
            parameters = {
                    @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique client-generated key (UUID) to prevent duplicated booking execution",
                            schema = @Schema(type = "string", format = "uuid")
                    )
            }
    )
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            // ADR-0012: obbligatorio. Se manca -> 428 (GlobalExceptionHandler); se non è un UUID -> 400
            @RequestHeader("Idempotency-Key") UUID idempotencyKey
    ) {
        UUID userId = currentUser.getRequiredUserId();
        BookingResult result = refugeBookingService.book(userId, idempotencyKey, refugeMapper.toCommand(request));
        BookingResponse body = refugeMapper.toResponse(result.booking());

        // retry con la stessa chiave: 200 con la prenotazione esistente, niente seconda creazione (ADR-0012)
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/my")
    @Operation(operationId = "findMyBookings", summary = "List current user's bookings", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<BookingResponse> getMyBookings(
            @ParameterObject @PageableDefault(size = 20, sort = "checkIn", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        UUID userId = currentUser.getRequiredUserId();
        return new PagedModel<>(refugeBookingService.getUserBookings(userId, pageable).map(refugeMapper::toResponse));
    }

    // la Location del POST punta qui
    @GetMapping("/{id}")
    @Operation(operationId = "getBooking", summary = "Get one of the current user's bookings", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID id) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(refugeMapper.toResponse(refugeBookingService.getBooking(userId, id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT) // solo per OpenAPI, a runtime decide la ResponseEntity
    @Operation(operationId = "cancelBooking", summary = "Cancel a confirmed booking", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> cancelBooking(@PathVariable UUID id){
        UUID userId = currentUser.getRequiredUserId();
        refugeBookingService.cancel(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ?sort= arriva dal client: un campo sconosciuto darebbe un errore di Spring Data (500)
    static void checkSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + SORTABLE_FIELDS);
            }
        }
    }
}
