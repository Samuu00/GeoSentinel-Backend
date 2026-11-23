// Test unitario di RefugeBookingService con repository finti di Mockito e orologio fisso, senza Spring né
// database. Riguarda l'idempotenza delle prenotazioni (un nuovo tentativo con la stessa Idempotency-Key non
// crea una seconda prenotazione) e i permessi per modificare i posti letto di un rifugio.
package it.unical.geosentinel.refuge;

import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingCommand;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingResult;
import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.refuge.infrastructure.persistence.BookingRepository;
import it.unical.geosentinel.refuge.infrastructure.persistence.RefugeRepository;
import it.unical.geosentinel.shared.domain.exception.IdempotencyKeyReusedException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica la gestione della chiave di idempotenza in {@link RefugeBookingService#book} e il controllo dei
 * permessi in {@link RefugeBookingService#updateCapacity}.
 */
class RefugeBookingServiceTest {

    /** Id dell'utente che prenota. */
    private static final UUID USER = UUID.randomUUID();

    /** Idempotency-Key già usata dall'utente per una prenotazione esistente. */
    private static final UUID KEY = UUID.randomUUID();

    /** Data di arrivo della prenotazione esistente. */
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);

    /** Repository dei rifugi finto. */
    private final RefugeRepository refuges = mock(RefugeRepository.class);

    /** Repository delle prenotazioni finto, su cui si verifica che non vengano salvate prenotazioni nuove. */
    private final BookingRepository bookings = mock(BookingRepository.class);

    /** Servizio sotto test, con l'orologio fermo a qualche giorno prima dell'arrivo. */
    private final RefugeBookingService service = new RefugeBookingService(refuges, bookings,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), mock(GeometryMapper.class));

    /** Id assegnato al rifugio di prova. */
    private final UUID refugeId = UUID.randomUUID();

    /** Rifugio di prova con 10 posti letto e un gestore casuale. */
    private final Refuge refuge = new Refuge("Rifugio Test",
            new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(11.85, 46.5)),
            10, UUID.randomUUID());

    /** Prenotazione già salvata con la chiave {@link #KEY}: due notti per due persone. */
    private final Booking previous;

    /**
     * Prepara i dati condivisi: assegna l'id al rifugio (normalmente lo farebbe JPA) e configura i repository
     * in modo che il rifugio esista e che la chiave {@link #KEY} dell'utente corrisponda già a una prenotazione.
     */
    RefugeBookingServiceTest() {
        ReflectionTestUtils.setField(refuge, "id", refugeId);
        previous = Booking.create(refuge, USER, KEY, CHECK_IN, CHECK_IN.plusDays(2), 2);
        when(refuges.findByIdForUpdate(refugeId)).thenReturn(Optional.of(refuge));
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(previous));
    }

    /**
     * Un nuovo tentativo con la stessa chiave e la stessa richiesta deve restituire la prenotazione esistente,
     * segnalata come non creata, senza salvarne una seconda.
     */
    @Test
    void retryWithSameKeyReturnsTheExistingBookingWithoutCreatingAnother() {
        BookingResult result = service.book(USER, KEY, new BookingCommand(refugeId, CHECK_IN, CHECK_IN.plusDays(2), 2));

        // "created" a false fa rispondere il controller con 200 invece che con 201.
        assertThat(result.created()).isFalse();
        assertThat(result.booking().guests()).isEqualTo(2);
        verify(bookings, never()).save(any());
    }

    /**
     * La stessa chiave usata con una richiesta diversa (qui una notte in più) indica un errore del client:
     * deve essere rifiutata con {@link IdempotencyKeyReusedException} e non deve salvare nulla.
     */
    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        assertThatThrownBy(() -> service.book(USER, KEY, new BookingCommand(refugeId, CHECK_IN, CHECK_IN.plusDays(3), 2)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(bookings, never()).save(any());
    }

    /**
     * Un utente che non è il gestore del rifugio e non è amministratore non può cambiare i posti letto: si
     * attende una {@link AccessDeniedException} e la capacità deve restare 10.
     */
    @Test
    void onlyTheRefugeManagerCanChangeCapacity() {
        assertThatThrownBy(() -> service.updateCapacity(UUID.randomUUID(), false, refugeId, 5))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(refuge.getCapacity()).isEqualTo(10);
    }

    /** Un amministratore può cambiare i posti letto di qualsiasi rifugio, anche se non ne è il gestore. */
    @Test
    void adminCanChangeCapacityOfAnyRefuge() {
        service.updateCapacity(UUID.randomUUID(), true, refugeId, 5);
        assertThat(refuge.getCapacity()).isEqualTo(5);
    }
}
