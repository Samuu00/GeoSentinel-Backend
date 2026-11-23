// Test dello strato web di BookingController con MockMvc in modalità standalone: il controller viene
// costruito a mano con le dipendenze finte di Mockito, senza contesto Spring né database. Controlla che la
// creazione di una prenotazione richieda un header Idempotency-Key valido.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.refuge.web.BookingController;
import it.unical.geosentinel.refuge.web.dto.RefugeMapper;
import it.unical.geosentinel.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la gestione dell'header {@code Idempotency-Key} su {@code POST /api/v1/bookings}.
 */
class BookingControllerTest {

    /** Corpo valido di una richiesta di prenotazione: due notti per due persone in un rifugio qualsiasi. */
    private static final String BODY = """
            {"refugeId":"%s","checkIn":"2026-10-10","checkOut":"2026-10-12","guests":2}
            """.formatted(UUID.randomUUID());

    /** MockMvc con il solo controller e il gestore globale degli errori, che produce le risposte ProblemDetail. */
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new BookingController(mock(RefugeBookingService.class), mock(CurrentUser.class),
                    mock(RefugeMapper.class)))
            .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
            .build();

    /**
     * Senza header {@code Idempotency-Key} la richiesta deve essere rifiutata con 428 Precondition Required e
     * con lo slug {@code precondition-required} nel campo {@code type} del ProblemDetail.
     */
    @Test
    void missingIdempotencyKeyIs428() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value("https://geosentinel.dev/problems/precondition-required"));
    }

    /**
     * Un header {@code Idempotency-Key} che non è un UUID deve essere rifiutato con 400 Bad Request.
     */
    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
