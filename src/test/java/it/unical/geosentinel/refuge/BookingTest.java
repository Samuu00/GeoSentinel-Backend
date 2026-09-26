package it.unical.geosentinel.refuge;

import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);

    private final Refuge refuge = new Refuge("Rifugio Test",
            new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(11.85, 46.5)),
            10, UUID.randomUUID());

    @Test
    void zeroNightsIsRejectedLikeTheDdlCheck() {
        assertThatThrownBy(() -> Booking.create(refuge, UUID.randomUUID(), UUID.randomUUID(), CHECK_IN, CHECK_IN, 1))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void cancelUsesTheGivenInstantNotTheSystemClock() {
        Booking booking = Booking.create(refuge, UUID.randomUUID(), UUID.randomUUID(), CHECK_IN, CHECK_IN.plusDays(2), 2);

        assertThatThrownBy(() -> booking.cancel(Instant.parse("2026-10-11T08:00:00Z")))
                .isInstanceOf(BusinessRuleViolationException.class);

        booking.cancel(Instant.parse("2026-10-09T08:00:00Z"));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
