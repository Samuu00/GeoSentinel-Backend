package it.unical.geosentinel.refuge.application;

import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.AvailabilityView;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingCommand;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingResult;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.BookingView;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.CreateRefugeCommand;
import it.unical.geosentinel.refuge.application.dto.RefugeBookingCommands.RefugeView;
import it.unical.geosentinel.refuge.domain.Booking;
import it.unical.geosentinel.refuge.domain.BookingStatus;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.refuge.infrastructure.persistence.BookingRepository;
import it.unical.geosentinel.refuge.infrastructure.persistence.RefugeRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.CapacityExceededException;
import it.unical.geosentinel.shared.domain.exception.IdempotencyKeyReusedException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefugeBookingService {
    private static final double MAX_SEARCH_RADIUS_METERS = 50_000.0;
    // Orizzonte del check-in: oltre, generate_series di maxNightlyOccupancy diventa enorme (sotto il lock del rifugio)
    static final int MAX_DAYS_AHEAD = 365;
    // Tetto per utente: un solo account non occupa un rifugio per mesi (nessuna API libera i posti altrui)
    static final int MAX_ACTIVE_BOOKINGS_PER_USER = 5;

    private final RefugeRepository refugeRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;
    private final GeometryMapper geometryMapper;

    public RefugeBookingService(RefugeRepository refugeRepository, BookingRepository bookingRepository, Clock clock,
                                GeometryMapper geometryMapper) {
        this.refugeRepository = refugeRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
        this.geometryMapper = geometryMapper;
    }

    private void validateBookingDates(LocalDate checkIn, LocalDate checkOut) {
        LocalDate today = LocalDate.now(clock);
        if (checkIn.isBefore(today)) {
            throw new BusinessRuleViolationException("Check-in date cannot be in the past");
        }
        if (checkIn.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessRuleViolationException("Check-in date cannot be more than " + MAX_DAYS_AHEAD + " days ahead");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new BusinessRuleViolationException("Check-out date must be strictly after check-in date");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut) > 14) {
            throw new BusinessRuleViolationException("A single booking cannot exceed 14 consecutive nights");
        }
    }

    // SELECT ... FOR UPDATE sul rifugio: serializza le prenotazioni dello stesso rifugio.
    // Con un findById semplice due richieste leggono la stessa occupazione e prenotano entrambe (overbooking).
    @Transactional
    @PreAuthorize("hasRole('HIKER')")
    public BookingResult book(UUID userId, UUID idempotencyKey, BookingCommand cmd){
        Refuge refuge = refugeRepository.findByIdForUpdate(cmd.refugeId())
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", cmd.refugeId()));

        // La chiave (ADR-0012) si cerca DOPO il lock: due retry simultanei sullo stesso rifugio si mettono in fila e il secondo
        // trova la riga del primo. Con chiavi uguali su rifugi diversi decide il vincolo UNIQUE (409).
        // Prima della validazione delle date: un retry arrivato a check-in passato restituisce comunque la prenotazione.
        Optional<Booking> existing = bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            Booking previous = existing.get();
            if (!previous.isSameRequest(cmd.refugeId(), cmd.checkIn(), cmd.checkOut(), cmd.guests())) {
                throw new IdempotencyKeyReusedException();
            }
            return new BookingResult(BookingView.from(previous), false);
        }

        validateBookingDates(cmd.checkIn(), cmd.checkOut());
        // ponytail: il lock è sul rifugio, non sull'utente: richieste simultanee su rifugi diversi possono superare il
        // tetto di qualche unità. Serve un lock per utente se diventa un problema
        if (bookingRepository.countByUserIdAndStatusAndCheckOutAfter(userId, BookingStatus.CONFIRMED, LocalDate.now(clock))
                >= MAX_ACTIVE_BOOKINGS_PER_USER) {
            throw new BusinessRuleViolationException("Too many active bookings: at most " + MAX_ACTIVE_BOOKINGS_PER_USER);
        }

        int maxOccupancy = bookingRepository.maxNightlyOccupancy(refuge.getId(), cmd.checkIn(), cmd.checkOut());
        int remainingCapacity = refuge.getCapacity() - maxOccupancy;
        if (cmd.guests() > remainingCapacity) {
            throw new CapacityExceededException(refuge.getId(), cmd.guests(), remainingCapacity);
        }

        Booking booking = Booking.create(refuge, userId, idempotencyKey, cmd.checkIn(), cmd.checkOut(), cmd.guests());
        return new BookingResult(BookingView.from(bookingRepository.save(booking)), true);
    }

    // Cancellazione dell'account: solo le prenotazioni non ancora iniziate (check-in da oggi in poi, UTC come
    // Booking.cancel). Quelle in corso o passate restano: sono storia, non posti da liberare
    @Transactional
    public void cancelAllFutureOf(UUID userId) {
        Instant now = Instant.now(clock);
        bookingRepository.findByUserIdAndStatusAndCheckInGreaterThanEqual(
                        userId, BookingStatus.CONFIRMED, LocalDate.ofInstant(now, ZoneOffset.UTC))
                .forEach(booking -> booking.cancel(now));
    }

    @Transactional
    public void cancel(UUID userId, UUID bookingId){
        // 404 anche se la prenotazione è di un altro utente: un 403/422 rivelerebbe che esiste
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        booking.cancel(Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public BookingView getBooking(UUID userId, UUID bookingId) {
        // 404 anche per una prenotazione altrui, come in cancel
        return BookingView.from(bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId)));
    }

    @Transactional(readOnly = true)
    public AvailabilityView availability(UUID refugeId, LocalDate from, LocalDate to) {
        validateBookingDates(from, to);
        Refuge refuge = refugeRepository.findById(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));

        List<Booking> overlapping = bookingRepository.findActiveOverlappingBookings(refugeId, from, to);

        Map<LocalDate, Integer> availablePerNight = new LinkedHashMap<>();
        LocalDate current = from;

        while (current.isBefore(to)) {
            LocalDate currentNight = current;
            int nightlyOccupancy = overlapping.stream()
                    .filter(b -> !b.getCheckIn().isAfter(currentNight) && b.getCheckOut().isAfter(currentNight))
                    .mapToInt(Booking::getGuests)
                    .sum();

            int availableBeds = Math.max(0, refuge.getCapacity() - nightlyOccupancy);
            availablePerNight.put(currentNight, availableBeds);
            current = current.plusDays(1);
        }
        return new AvailabilityView(refuge.getId(), refuge.getCapacity(), from, to, availablePerNight);
    }

    @Transactional(readOnly = true)
    public RefugeView getRefuge(UUID refugeId){
        return RefugeView.from(getRefugeOrThrow(refugeId), geometryMapper);
    }
    // per le altre feature (es. maintenance), che non usano RefugeRepository
    @Transactional(readOnly = true)
    public Refuge getRefugeOrThrow(UUID refugeId){
        return refugeRepository.findById(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));
    }

    @Transactional(readOnly = true)
    public List<RefugeView> findNearby(double lon, double lat, double radiusMeters, int limit){
        double radius = Math.min(radiusMeters, MAX_SEARCH_RADIUS_METERS); // niente query-bomba, come per sentieri e POI
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return refugeRepository.findNearby(lon, lat, radius, boundedLimit)
                .stream()
                .map(r -> RefugeView.from(r, geometryMapper))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<BookingView> getUserBookings(UUID userId, Pageable pageable){
        return bookingRepository.findByUserId(userId, pageable).map(BookingView::from);
    }

    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public RefugeView createRefuge(CreateRefugeCommand cmd){
        Refuge refuge = new Refuge(cmd.name(), cmd.location(), cmd.capacity(), cmd.managerId());
        refuge.describe(cmd.description(), cmd.altitudeM(), cmd.season(), cmd.phone(), cmd.amenities());
        return RefugeView.from(refugeRepository.save(refuge), geometryMapper);
    }

    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public RefugeView updateCapacity(UUID actorId, boolean actorIsAdmin, UUID refugeId, Integer capacity){
        Refuge refuge = refugeRepository.findByIdForUpdate(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));
        // il ruolo non basta: solo il gestore del rifugio o un ADMIN ne cambiano la capacità (403, il rifugio è comunque visibile)
        if (!actorIsAdmin && !refuge.isManagedBy(actorId)) {
            throw new AccessDeniedException("Only the refuge manager can change its capacity");
        }
        // Mai sotto gli ospiti già confermati per una notte da stanotte in poi (409): sotto il lock del rifugio,
        // quindi nessuna prenotazione può arrivare tra il controllo e la modifica
        LocalDate today = LocalDate.now(clock);
        bookingRepository.lastConfirmedCheckOut(refugeId)
                .filter(lastCheckOut -> lastCheckOut.isAfter(today))
                .ifPresent(lastCheckOut -> {
                    int occupancy = bookingRepository.maxNightlyOccupancy(refugeId, today, lastCheckOut);
                    if (capacity < occupancy) {
                        throw CapacityExceededException.belowConfirmedBookings(refugeId, capacity, occupancy);
                    }
                });
        refuge.changeCapacity(capacity);
        return RefugeView.from(refuge, geometryMapper);
    }
}
