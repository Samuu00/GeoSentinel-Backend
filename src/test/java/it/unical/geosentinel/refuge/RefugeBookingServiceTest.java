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

class RefugeBookingServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);

    private final RefugeRepository refuges = mock(RefugeRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final RefugeBookingService service = new RefugeBookingService(refuges, bookings,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), mock(GeometryMapper.class));

    private final UUID refugeId = UUID.randomUUID();
    private final Refuge refuge = new Refuge("Rifugio Test",
            new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(11.85, 46.5)),
            10, UUID.randomUUID());
    private final Booking previous;

    RefugeBookingServiceTest() {
        ReflectionTestUtils.setField(refuge, "id", refugeId);
        previous = Booking.create(refuge, USER, KEY, CHECK_IN, CHECK_IN.plusDays(2), 2);
        when(refuges.findByIdForUpdate(refugeId)).thenReturn(Optional.of(refuge));
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(previous));
    }

    @Test
    void retryWithSameKeyReturnsTheExistingBookingWithoutCreatingAnother() {
        BookingResult result = service.book(USER, KEY, new BookingCommand(refugeId, CHECK_IN, CHECK_IN.plusDays(2), 2));

        assertThat(result.created()).isFalse(); // il controller risponde 200, non 201
        assertThat(result.booking().guests()).isEqualTo(2);
        verify(bookings, never()).save(any());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        assertThatThrownBy(() -> service.book(USER, KEY, new BookingCommand(refugeId, CHECK_IN, CHECK_IN.plusDays(3), 2)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(bookings, never()).save(any());
    }

    @Test
    void onlyTheRefugeManagerCanChangeCapacity() {
        assertThatThrownBy(() -> service.updateCapacity(UUID.randomUUID(), false, refugeId, 5))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(refuge.getCapacity()).isEqualTo(10);
    }

    @Test
    void adminCanChangeCapacityOfAnyRefuge() {
        service.updateCapacity(UUID.randomUUID(), true, refugeId, 5);
        assertThat(refuge.getCapacity()).isEqualTo(5);
    }
}
