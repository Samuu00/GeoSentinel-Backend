// Service dei rifugi e delle prenotazioni. Gestisce la ricerca e la creazione dei rifugi, la modifica
// dei posti letto e il ciclo di vita delle prenotazioni. Le prenotazioni dello stesso rifugio sono
// serializzate da un lock pessimistico, così la capacità, controllata notte per notte, non viene mai
// superata da richieste concorrenti.
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Logica applicativa dei rifugi e delle prenotazioni, con l'idempotenza delle prenotazioni e i
 * controlli di capacità.
 */
@Service
public class RefugeBookingService {
    /** Raggio massimo della ricerca dei rifugi, in metri (50 km). */
    private static final double MAX_SEARCH_RADIUS_METERS = 50_000.0;
    /**
     * Massimo anticipo, in giorni, della data di arrivo. Oltre questo orizzonte la serie di notti generata
     * da maxNightlyOccupancy diventerebbe molto lunga, e la query gira mentre il rifugio è bloccato.
     */
    static final int MAX_DAYS_AHEAD = 365;
    /**
     * Numero massimo di prenotazioni attive per utente: impedisce che un solo account occupi un rifugio
     * per mesi, dato che nessuna API permette di liberare i posti prenotati da altri.
     */
    static final int MAX_ACTIVE_BOOKINGS_PER_USER = 5;

    private final RefugeRepository refugeRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;
    private final GeometryMapper geometryMapper;

    /**
     * @param refugeRepository  repository dei rifugi
     * @param bookingRepository repository delle prenotazioni
     * @param clock             orologio dell'applicazione, per la data di oggi
     * @param geometryMapper    conversione delle geometrie nei DTO
     */
    public RefugeBookingService(RefugeRepository refugeRepository, BookingRepository bookingRepository, Clock clock,
                                GeometryMapper geometryMapper) {
        this.refugeRepository = refugeRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
        this.geometryMapper = geometryMapper;
    }

    /**
     * Controlla un intervallo di date: arrivo non nel passato e non oltre {@link #MAX_DAYS_AHEAD} giorni,
     * partenza successiva all'arrivo e al massimo 14 notti consecutive.
     *
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @throws BusinessRuleViolationException se una delle regole non è rispettata
     */
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
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > 14) {
            throw new BusinessRuleViolationException("A single booking cannot exceed 14 consecutive nights");
        }
    }

    /**
     * Prenota posti letto in un rifugio. Il rifugio viene letto con {@code SELECT ... FOR UPDATE}, che
     * serializza le prenotazioni dello stesso rifugio: con una lettura normale due richieste potrebbero
     * vedere la stessa occupazione e prenotare entrambe, superando la capacità.
     *
     * @param userId         utente autenticato
     * @param idempotencyKey chiave di idempotenza inviata dal client
     * @param cmd            rifugio, date e numero di ospiti
     * @return la prenotazione e l'indicazione se è stata creata ora o restituita per un ritentativo
     * @throws ResourceNotFoundException      se il rifugio non esiste
     * @throws IdempotencyKeyReusedException  se la chiave è già stata usata per una richiesta diversa
     * @throws BusinessRuleViolationException se le date non sono valide o l'utente ha troppe prenotazioni attive
     * @throws CapacityExceededException      se in una delle notti non ci sono abbastanza posti
     */
    @Transactional
    @PreAuthorize("hasRole('HIKER')")
    public BookingResult book(UUID userId, UUID idempotencyKey, BookingCommand cmd){
        Refuge refuge = refugeRepository.findByIdForUpdate(cmd.refugeId())
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", cmd.refugeId()));

        // La chiave di idempotenza si cerca dopo aver preso il lock: due ritentativi simultanei sullo stesso
        // rifugio si mettono in fila e il secondo trova la riga creata dal primo. Con la stessa chiave su
        // rifugi diversi decide il vincolo UNIQUE del database (409). La ricerca avviene anche prima della
        // validazione delle date, così un ritentativo arrivato dopo il giorno di arrivo restituisce comunque
        // la prenotazione già creata.
        Optional<Booking> existing = bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            Booking previous = existing.get();
            if (!previous.isSameRequest(cmd.refugeId(), cmd.checkIn(), cmd.checkOut(), cmd.guests())) {
                throw new IdempotencyKeyReusedException();
            }
            return new BookingResult(BookingView.from(previous), false);
        }

        validateBookingDates(cmd.checkIn(), cmd.checkOut());
        // Limite noto: il lock è sul rifugio e non sull'utente, quindi richieste simultanee dello stesso
        // utente su rifugi diversi possono superare il tetto di qualche unità. Se diventasse un problema
        // servirebbe un lock per utente.
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

    /**
     * Annulla le prenotazioni future di un utente, durante la cancellazione dell'account. Riguarda solo
     * quelle non ancora iniziate (arrivo da oggi in poi, in UTC come in Booking.cancel): quelle in corso o
     * passate restano, perché sono storico e non posti da liberare.
     *
     * @param userId utente da cancellare
     */
    @Transactional
    public void cancelAllFutureOf(UUID userId) {
        Instant now = Instant.now(clock);
        bookingRepository.findByUserIdAndStatusAndCheckInGreaterThanEqual(
                        userId, BookingStatus.CONFIRMED, LocalDate.ofInstant(now, ZoneOffset.UTC))
                .forEach(booking -> booking.cancel(now));
    }

    /**
     * Annulla una prenotazione dell'utente.
     *
     * @param userId    utente autenticato
     * @param bookingId prenotazione da annullare
     * @throws ResourceNotFoundException      se non esiste o non è dell'utente
     * @throws BusinessRuleViolationException se il giorno di arrivo è già passato
     */
    @Transactional
    public void cancel(UUID userId, UUID bookingId){
        // Si risponde 404 anche se la prenotazione è di un altro utente: un 403 o un 422 rivelerebbero che esiste.
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        booking.cancel(Instant.now(clock));
    }

    /**
     * @param userId    utente autenticato
     * @param bookingId prenotazione richiesta
     * @return la prenotazione
     * @throws ResourceNotFoundException se non esiste o non è dell'utente
     */
    @Transactional(readOnly = true)
    public BookingView getBooking(UUID userId, UUID bookingId) {
        // 404 anche per una prenotazione altrui, come in cancel.
        return BookingView.from(bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId)));
    }

    /**
     * Calcola i posti liberi di un rifugio per ogni notte dell'intervallo. Le prenotazioni sovrapposte si
     * leggono con una sola query e il conteggio per notte si fa in memoria. L'intervallo segue le stesse
     * regole di una prenotazione (al massimo 14 notti). Il totale è quello che pagherebbe una prenotazione
     * fatta adesso: prezzo attuale × notti × ospiti.
     *
     * @param refugeId rifugio
     * @param from     prima notte
     * @param to       giorno di fine, escluso
     * @param guests   ospiti su cui calcolare il totale, positivo
     * @return capacità totale, posti liberi per notte in ordine di data e totale
     * @throws BusinessRuleViolationException se l'intervallo non è valido
     * @throws ResourceNotFoundException      se il rifugio non esiste
     */
    @Transactional(readOnly = true)
    public AvailabilityView availability(UUID refugeId, LocalDate from, LocalDate to, int guests) {
        validateBookingDates(from, to);
        if (guests <= 0) {
            throw new BusinessRuleViolationException("Guests must be a positive integer");
        }
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
        return new AvailabilityView(refuge.getId(), refuge.getCapacity(), from, to, availablePerNight, guests,
                refuge.totalPriceCents(ChronoUnit.DAYS.between(from, to), guests));
    }

    /**
     * @param refugeId rifugio
     * @return la vista del rifugio
     * @throws ResourceNotFoundException se non esiste
     */
    @Transactional(readOnly = true)
    public RefugeView getRefuge(UUID refugeId){
        return RefugeView.from(getRefugeOrThrow(refugeId), geometryMapper);
    }
    /**
     * Restituisce l'entità del rifugio per le altre feature (ad esempio la manutenzione), che non usano il
     * repository di questa.
     *
     * @param refugeId rifugio
     * @return l'entità
     * @throws ResourceNotFoundException se non esiste
     */
    @Transactional(readOnly = true)
    public Refuge getRefugeOrThrow(UUID refugeId){
        return refugeRepository.findById(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));
    }

    /**
     * Cerca i rifugi attorno a un punto, dal più vicino.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri, al massimo 50 km
     * @param limit        numero massimo di risultati, al massimo 50
     * @return i rifugi trovati
     */
    @Transactional(readOnly = true)
    public List<RefugeView> findNearby(double lon, double lat, double radiusMeters, int limit){
        // Raggio e risultati limitati, come per sentieri e POI, per evitare query troppo pesanti.
        double radius = Math.min(radiusMeters, MAX_SEARCH_RADIUS_METERS);
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return refugeRepository.findNearby(lon, lat, radius, boundedLimit)
                .stream()
                .map(r -> RefugeView.from(r, geometryMapper))
                .toList();
    }

    /**
     * @param userId   utente autenticato
     * @param pageable paginazione e ordinamento
     * @return la pagina delle prenotazioni dell'utente
     */
    @Transactional(readOnly = true)
    public Page<BookingView> getUserBookings(UUID userId, Pageable pageable){
        return bookingRepository.findByUserId(userId, pageable).map(BookingView::from);
    }

    /**
     * Crea un rifugio con i suoi dati descrittivi.
     *
     * @param cmd dati del rifugio
     * @return la vista del rifugio creato
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public RefugeView createRefuge(CreateRefugeCommand cmd){
        Refuge refuge = new Refuge(cmd.name(), cmd.location(), cmd.capacity(), cmd.managerId());
        refuge.describe(cmd.description(), cmd.altitudeM(), cmd.season(), cmd.phone(), cmd.amenities());
        return RefugeView.from(refugeRepository.save(refuge), geometryMapper);
    }

    /**
     * Cambia i posti letto di un rifugio. Il rifugio viene bloccato con un lock pessimistico, così nessuna
     * prenotazione può arrivare tra il controllo dell'occupazione e la modifica.
     *
     * @param actorId      utente che chiede la modifica
     * @param actorIsAdmin {@code true} se l'utente è un amministratore
     * @param refugeId     rifugio
     * @param capacity     nuova capacità
     * @return la vista aggiornata
     * @throws ResourceNotFoundException  se il rifugio non esiste
     * @throws AccessDeniedException      se l'utente non è il gestore del rifugio né un amministratore
     * @throws CapacityExceededException  se la nuova capacità è inferiore agli ospiti confermati in una notte futura
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public RefugeView updateCapacity(UUID actorId, boolean actorIsAdmin, UUID refugeId, Integer capacity){
        Refuge refuge = refugeRepository.findByIdForUpdate(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));
        // Il ruolo non basta: solo il gestore di questo rifugio o un ADMIN possono cambiarne la capacità.
        // Si risponde 403 e non 404 perché il rifugio è comunque pubblico.
        if (!actorIsAdmin && !refuge.isManagedBy(actorId)) {
            throw new AccessDeniedException("Only the refuge manager can change its capacity");
        }
        // La capacità non può scendere sotto gli ospiti già confermati in una qualsiasi notte da stanotte in
        // poi (409). Il controllo avviene sotto il lock del rifugio.
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

    /**
     * Imposta o toglie il prezzo a persona per notte di un rifugio. Le prenotazioni già fatte conservano il
     * totale di allora. Il rifugio si blocca come in {@link #book}: una prenotazione in corso usa il prezzo
     * di prima o quello nuovo, mai uno a metà.
     *
     * @param actorId            utente che chiede la modifica
     * @param actorIsAdmin       {@code true} se l'utente è un amministratore
     * @param refugeId           rifugio
     * @param pricePerNightCents nuovo prezzo in centesimi, oppure {@code null} per toglierlo
     * @return la vista aggiornata
     * @throws ResourceNotFoundException      se il rifugio non esiste
     * @throws AccessDeniedException          se l'utente non è il gestore del rifugio né un amministratore
     * @throws BusinessRuleViolationException se il prezzo è fuori dall'intervallo
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public RefugeView updatePrice(UUID actorId, boolean actorIsAdmin, UUID refugeId, Integer pricePerNightCents) {
        Refuge refuge = refugeRepository.findByIdForUpdate(refugeId)
                .orElseThrow(() -> new ResourceNotFoundException("Refuge", refugeId));
        // Stessa regola della capacità: il ruolo non basta, serve essere il gestore di questo rifugio o un ADMIN.
        if (!actorIsAdmin && !refuge.isManagedBy(actorId)) {
            throw new AccessDeniedException("Only the refuge manager can change its price");
        }
        refuge.changePrice(pricePerNightCents);
        return RefugeView.from(refuge, geometryMapper);
    }
}
