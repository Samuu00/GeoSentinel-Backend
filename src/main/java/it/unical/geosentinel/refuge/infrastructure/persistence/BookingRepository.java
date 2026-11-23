// Repository Spring Data JPA delle prenotazioni. Contiene le query che calcolano l'occupazione di un
// rifugio notte per notte, usate per non superare i posti letto, e le ricerche per utente.
package it.unical.geosentinel.refuge.infrastructure.persistence;

import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accesso ai dati della tabella bookings.
 */
public interface BookingRepository extends JpaRepository<Booking, UUID>{
    /**
     * Calcola il massimo numero di ospiti confermati in una singola notte dell'intervallo. La query genera
     * una riga per ogni notte con {@code generate_series}, somma gli ospiti delle prenotazioni confermate
     * che la comprendono e prende il valore massimo: è la notte più piena a decidere se c'è posto.
     *
     * @param refugeId rifugio
     * @param checkIn  prima notte
     * @param checkOut giorno di partenza, esclusa
     * @return il numero massimo di ospiti in una notte, 0 se non ci sono prenotazioni
     */
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

    /**
     * Ultimo giorno di partenza tra le prenotazioni confermate: indica fin dove controllare l'occupazione
     * quando il gestore riduce la capacità.
     *
     * @param refugeId rifugio
     * @return la data, oppure vuoto se non ci sono prenotazioni confermate
     */
    @Query(value = "SELECT MAX(check_out) FROM bookings WHERE refuge_id = :refugeId AND status = 'CONFIRMED'", nativeQuery = true)
    Optional<LocalDate> lastConfirmedCheckOut(@Param("refugeId") UUID refugeId);

    /**
     * Cerca una prenotazione solo tra quelle dell'utente, così una prenotazione altrui risulta inesistente.
     *
     * @param id     identificativo della prenotazione
     * @param userId utente proprietario
     * @return la prenotazione, se esiste ed è dell'utente
     */
    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);
    /**
     * Cerca la prenotazione già creata con una chiave di idempotenza, per rispondere ai ritentativi.
     *
     * @param userId         utente
     * @param idempotencyKey chiave inviata dal client
     * @return la prenotazione, se esiste
     */
    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey);
    /**
     * @param userId   utente
     * @param pageable paginazione e ordinamento
     * @return la pagina delle prenotazioni dell'utente
     */
    Page<Booking> findByUserId(UUID userId, Pageable pageable);
    /**
     * Prenotazioni di un utente in uno stato con arrivo a partire da una data, usate per annullare quelle
     * future alla cancellazione dell'account.
     *
     * @param userId utente
     * @param status stato cercato
     * @param from   prima data di arrivo inclusa
     * @return le prenotazioni trovate
     */
    List<Booking> findByUserIdAndStatusAndCheckInGreaterThanEqual(UUID userId, BookingStatus status, LocalDate from);
    /**
     * Conta le prenotazioni di un utente in uno stato con partenza successiva a una data, cioè non ancora
     * concluse. Serve al limite di prenotazioni attive per utente controllato in RefugeBookingService.
     *
     * @param userId utente
     * @param status stato cercato
     * @param date   data di riferimento
     * @return il numero di prenotazioni
     */
    long countByUserIdAndStatusAndCheckOutAfter(UUID userId, BookingStatus status, LocalDate date);
    /**
     * Prenotazioni confermate che hanno almeno una notte nell'intervallo indicato, ordinate per arrivo.
     *
     * @param refugeId rifugio
     * @param from     primo giorno dell'intervallo
     * @param to       giorno di fine dell'intervallo, escluso
     * @return le prenotazioni sovrapposte
     */
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
