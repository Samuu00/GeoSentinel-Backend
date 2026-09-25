package it.unical.geosentinel.refuge.application.dto;

import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.locationtech.jts.geom.Point;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public class RefugeBookingCommands {
    public record CreateRefugeCommand(
            String name,
            Point location,
            Integer capacity,
            UUID managerId
    ) {}
    public record BookingCommand(
            UUID refugeId,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer guests
    ) {}
    public record RefugeView(
            UUID id,
            String name,
            GeoPointDto location,
            Integer capacity,
            UUID managerId
    ) {
        // GeoPointDto e non Point: le view non contengono geometrie JTS (non serializzabili in JSON)
        public static RefugeView from(Refuge r, GeometryMapper geo) {
            return new RefugeView(r.getId(), r.getName(), geo.toDto(r.getLocation()), r.getCapacity(), r.getManagerId());
        }
    }
    public record BookingView(
            UUID id,
            UUID refugeId,
            UUID userId,
            LocalDate checkIn,
            LocalDate checkOut,
            int guests,
            BookingStatus status
    ) {
        public static BookingView from(Booking b) {
            return new BookingView(
                    b.getId(),
                    b.getRefuge().getId(),
                    b.getUserId(),
                    b.getCheckIn(),
                    b.getCheckOut(),
                    b.getGuests(),
                    b.getStatus()
            );
        }
    }
    // created = false: retry con la stessa Idempotency-Key, il controller risponde 200 invece di 201 (D12)
    public record BookingResult(BookingView booking, boolean created) {}
    public record AvailabilityView(
            UUID refugeId,
            int totalCapacity,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, Integer> availableBedsPerNight
    ) {}
}
