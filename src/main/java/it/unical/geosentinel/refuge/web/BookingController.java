// Controller REST delle prenotazioni, sotto /api/v1/bookings. Tutte le rotte richiedono il token e
// lavorano solo sulle prenotazioni dell'utente autenticato. La creazione richiede l'header
// Idempotency-Key, così l'app può ripetere la richiesta senza creare prenotazioni doppie.
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

/**
 * Espone creazione, lettura, elenco e annullamento delle prenotazioni dell'utente corrente.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Endpoints for managing refuge bed reservations")
public class BookingController {
    /**
     * Campi ammessi nel parametro sort. status è escluso: l'enum è salvato come stringa e l'ordinamento
     * sarebbe alfabetico, non quello atteso.
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of("checkIn", "checkOut", "createdAt");

    private final RefugeBookingService refugeBookingService;
    private final CurrentUser currentUser;
    private final RefugeMapper refugeMapper;

    /**
     * @param refugeBookingService service dei rifugi e delle prenotazioni
     * @param currentUser          accesso all'utente autenticato
     * @param refugeMapper         mapper tra DTO REST e oggetti del service
     */
    public BookingController(RefugeBookingService refugeBookingService, CurrentUser currentUser, RefugeMapper refugeMapper) {
        this.refugeBookingService = refugeBookingService;
        this.currentUser = currentUser;
        this.refugeMapper = refugeMapper;
    }

    /**
     * Prenota posti letto in un rifugio. Con un ritentativo che usa la stessa Idempotency-Key risponde
     * 200 con la prenotazione già esistente, senza crearne una seconda.
     *
     * @param request        rifugio, date e ospiti, validati
     * @param idempotencyKey chiave generata dal client
     * @return 201 con la prenotazione creata, oppure 200 per un ritentativo
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED) // solo per la documentazione OpenAPI: a runtime lo status lo decide la ResponseEntity
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
            // Header obbligatorio: se manca GlobalExceptionHandler risponde 428, se non è un UUID 400.
            @RequestHeader("Idempotency-Key") UUID idempotencyKey
    ) {
        UUID userId = currentUser.getRequiredUserId();
        BookingResult result = refugeBookingService.book(userId, idempotencyKey, refugeMapper.toCommand(request));
        BookingResponse body = refugeMapper.toResponse(result.booking());

        // Ritentativo con la stessa chiave: 200 con la prenotazione esistente, nessuna seconda creazione.
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
     * Elenca le prenotazioni dell'utente corrente, di default dalla data di arrivo più lontana.
     *
     * @param pageable paginazione; si può ordinare per checkIn, checkOut o createdAt
     * @return la pagina di prenotazioni
     */
    @GetMapping("/my")
    @Operation(operationId = "findMyBookings", summary = "List current user's bookings", security = @SecurityRequirement(name = "bearerAuth"))
    public PagedModel<BookingResponse> getMyBookings(
            @ParameterObject @PageableDefault(size = 20, sort = "checkIn", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        checkSortable(pageable.getSort());
        UUID userId = currentUser.getRequiredUserId();
        return new PagedModel<>(refugeBookingService.getUserBookings(userId, pageable).map(refugeMapper::toResponse));
    }

    /**
     * Restituisce una prenotazione dell'utente corrente. È la risorsa indicata dall'header Location della
     * creazione.
     *
     * @param id identificativo della prenotazione
     * @return 200 con la prenotazione, 404 se non esiste o è di un altro utente
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getBooking", summary = "Get one of the current user's bookings", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID id) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(refugeMapper.toResponse(refugeBookingService.getBooking(userId, id)));
    }

    /**
     * @param id identificativo della prenotazione
     * @return 204; 404 se non esiste o è di un altro utente; 422 se il giorno di arrivo è passato
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT) // solo per la documentazione OpenAPI: a runtime lo status lo decide la ResponseEntity
    @Operation(operationId = "cancelBooking", summary = "Cancel a confirmed booking", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> cancelBooking(@PathVariable UUID id){
        UUID userId = currentUser.getRequiredUserId();
        refugeBookingService.cancel(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Controlla il parametro sort, che arriva dal client: un campo sconosciuto provocherebbe un errore di
     * Spring Data e quindi un 500.
     *
     * @param sort ordinamento richiesto
     * @throws ResponseStatusException 400 se compare un campo non ammesso
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
