package it.unical.geosentinel.refuge.infrastructure.persistence;

import it.unical.geosentinel.refuge.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>{
    @Query(value = """
            SELECT COALESCE(MAX(nightly.total_guests), 0)
            FROM (
                SELECT d.night, COALESCE(SUM(b.guests), 0) AS total_guests
                FROM generate_series(
                    CAST(:checkIn AS date),
                    CAST(:checkOut AS date) - INTERVAL '1 day',
                    INTERVAL '1 day'
                ) AS d(night)
                LEFT JOIN bookings b ON b.refuge_id = :refugeId
                    AND b.status = 'CONFIRMED'
                    AND b.check_in <= d.night
                    AND b.check_out > d.night
                GROUP BY d.night
            ) AS nightly
            """, nativeQuery = true)
    int maxNightlyOccupancy(
            @Param("refugeId") UUID refugeId,
            @Param("checkIn") LocalDate checkIn,
            @Param("checkOut") LocalDate checkOut
    );

    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);
    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey);
    List<Booking> findByUserIdOrderByCheckInDesc(UUID userId);
    @Query(value = """
            SELECT b.* FROM bookings b
            WHERE b.refuge_id = :refugeId
              AND b.status = 'CONFIRMED'
              AND b.check_in < :to
              AND b.check_out > :from
            ORDER BY b.check_in ASC
            """, nativeQuery = true)
    List<Booking> findActiveOverlappingBookings(
            @Param("refugeId") UUID refugeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
