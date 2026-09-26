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

class BookingControllerTest {

    private static final String BODY = """
            {"refugeId":"%s","checkIn":"2026-10-10","checkOut":"2026-10-12","guests":2}
            """.formatted(UUID.randomUUID());

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new BookingController(mock(RefugeBookingService.class), mock(CurrentUser.class),
                    mock(RefugeMapper.class)))
            .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
            .build();

    @Test
    void missingIdempotencyKeyIs428() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value("https://geosentinel.dev/problems/precondition-required"));
    }

    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
