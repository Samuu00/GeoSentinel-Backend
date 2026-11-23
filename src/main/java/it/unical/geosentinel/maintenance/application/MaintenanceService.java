// Servizio applicativo della manutenzione: apre, modifica, assegna e chiude i ticket di manutenzione su
// sentieri, rifugi e segnalazioni di pericolo. Gestisce il controllo di concorrenza ottimistico basato sulla
// versione del ticket e mantiene coerente la cache dei ticket per sentiero.
package it.unical.geosentinel.maintenance.application;

import it.unical.geosentinel.identity.application.UserQueryService;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.CreateMaintenanceTicketCommand;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketQuery;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.TicketView;
import it.unical.geosentinel.maintenance.application.dto.MaintenanceTicketCommands.UpdateTicketCommand;
import it.unical.geosentinel.maintenance.domain.MaintenanceTicket;
import it.unical.geosentinel.maintenance.domain.TicketStatus;
import it.unical.geosentinel.maintenance.infrastructure.persistence.MaintenanceTicketRepository;
import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.shared.config.CacheConfig;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.OptimisticConflictException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.trail.application.HazardReportService;
import it.unical.geosentinel.trail.application.TrailService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Casi d'uso dei ticket di manutenzione, riservati ai gestori del parco (e agli amministratori, che grazie
 * alla gerarchia dei ruoli hanno anche il ruolo PARK_MANAGER).
 * <p>
 * L'elenco dei ticket di un sentiero è salvato in cache. Dopo ogni modifica si svuota la voce del sentiero
 * del ticket, letta dal risultato ({@code #result.trailId()}): il sentiero di un ticket non cambia mai, quindi
 * quella è sempre la voce giusta. I ticket di un rifugio non hanno sentiero e non finiscono in cache.
 */
@Service
@PreAuthorize("hasRole('PARK_MANAGER')")
public class MaintenanceService {
    /** Espressione SpEL della chiave da svuotare: il sentiero del ticket restituito. */
    private static final String EVICT_KEY = "#result.trailId()";
    /** Si svuota la cache solo se il ticket ha un sentiero. */
    private static final String EVICT_IF_TRAIL = "#result.trailId() != null";

    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final TrailService trailService;
    private final RefugeBookingService refugeBookingService;
    private final HazardReportService hazardReportService;
    private final UserQueryService userQueryService;
    private final Clock clock;

    /**
     * @param maintenanceTicketRepository repository dei ticket
     * @param trailService servizio dei sentieri, per caricare e verificare il sentiero
     * @param refugeBookingService servizio dei rifugi, per caricare il rifugio
     * @param hazardReportService servizio delle segnalazioni, per caricare la segnalazione
     * @param userQueryService servizio degli utenti, per verificare l'assegnatario
     * @param clock orologio iniettato, sostituibile nei test
     */
    public MaintenanceService(MaintenanceTicketRepository maintenanceTicketRepository,
                              TrailService trailService,
                              RefugeBookingService refugeBookingService,
                              HazardReportService hazardReportService,
                              UserQueryService userQueryService,
                              Clock clock
    ) {
        this.maintenanceTicketRepository = maintenanceTicketRepository;
        this.trailService = trailService;
        this.refugeBookingService = refugeBookingService;
        this.hazardReportService = hazardReportService;
        this.userQueryService = userQueryService;
        this.clock = clock;
    }

    /**
     * Legge un ticket con sentiero e rifugio.
     *
     * @param id id del ticket
     * @return il ticket
     * @throws ResourceNotFoundException se il ticket non esiste
     */
    @Transactional(readOnly = true)
    public TicketView getTicket(UUID id) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaintenanceTicket", id));
        return TicketView.from(ticket);
    }

    /**
     * Ricerca paginata dei ticket con filtri facoltativi su stato, priorità e assegnatario.
     *
     * @param query filtri; i campi null non filtrano
     * @param pageable pagina e ordinamento
     * @return la pagina di ticket
     */
    @Transactional(readOnly = true)
    public Page<TicketView> findTickets(TicketQuery query, Pageable pageable){
        return maintenanceTicketRepository.searchTickets(query.status(), query.priority(), query.assignedTo(), pageable)
                .map(TicketView::from);
    }

    /**
     * Ticket di un sentiero, dal più recente. Il risultato è salvato nella cache per sentiero.
     *
     * @param trailId id del sentiero
     * @return i ticket del sentiero
     * @throws ResourceNotFoundException se il sentiero non esiste (si preferisce un 404 a una lista vuota)
     */
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = "#trailId")
    public List<TicketView> byTrail(UUID trailId) {
        // Il controllo sul sentiero serve solo a rispondere 404 invece di una lista vuota.
        trailService.getTrailOrThrow(trailId);
        return maintenanceTicketRepository.findByTrailIdOrderByCreatedAtDesc(trailId).stream()
                .map(TicketView::from)
                .toList();
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    /**
     * Apre un ticket su un sentiero, un rifugio o una segnalazione di pericolo. Un ticket nato da una
     * segnalazione eredita il sentiero della segnalazione.
     *
     * @param cmd dati del ticket
     * @return il ticket salvato, con id e versione già valorizzati
     * @throws BusinessRuleViolationException se non è indicato esattamente un obiettivo
     * @throws ResourceNotFoundException se sentiero, rifugio o segnalazione non esistono
     */
    public TicketView createTicket(CreateMaintenanceTicketCommand cmd){
        // Si accetta un solo obiettivo: se ne arrivassero più di uno, tutti tranne uno verrebbero ignorati
        // senza avvisare il client.
        long targets = Stream.of(cmd.trailId(), cmd.refugeId(), cmd.hazardReportId()).filter(Objects::nonNull).count();
        if (targets != 1) {
            throw new BusinessRuleViolationException("A ticket must reference exactly one of trailId, refugeId, hazardReportId");
        }

        MaintenanceTicket ticket;
        if (cmd.hazardReportId() != null) {
            ticket = MaintenanceTicket.forHazard(cmd.title(), cmd.description(), cmd.priority(),
                    hazardReportService.getHazardOrThrow(cmd.hazardReportId()));
        } else if (cmd.trailId() != null) {
            ticket = MaintenanceTicket.forTrail(cmd.title(), cmd.description(), cmd.priority(),
                    trailService.getTrailOrThrow(cmd.trailId()));
        } else {
            ticket = MaintenanceTicket.forRefuge(cmd.title(), cmd.description(), cmd.priority(),
                    refugeBookingService.getRefugeOrThrow(cmd.refugeId()));
        }

        // saveAndFlush scrive subito sul database, così id, created_at e version sono valorizzati prima di
        // costruire la vista (la version finisce nell'ETag della risposta).
        return TicketView.from(maintenanceTicketRepository.saveAndFlush(ticket));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    /**
     * Modifica titolo, descrizione e priorità.
     *
     * @param id id del ticket
     * @param expectedVersion versione letta dal client, ricevuta nell'header If-Match
     * @param cmd nuovi dati
     * @return il ticket aggiornato, con la nuova versione
     * @throws OptimisticConflictException se nel frattempo qualcun altro ha modificato il ticket
     * @throws BusinessRuleViolationException se il ticket è già chiuso
     */
    public TicketView updateDetails(UUID id, long expectedVersion, UpdateTicketCommand cmd) {
        MaintenanceTicket ticket = loadForUpdate(id, expectedVersion);
        ticket.updateDetails(cmd.title(), cmd.description(), cmd.priority());
        return flushAndView(ticket);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    /**
     * Assegna il ticket a un utente attivo.
     *
     * @param id id del ticket
     * @param expectedVersion versione letta dal client
     * @param operatorId utente a cui assegnare il lavoro
     * @return il ticket aggiornato
     * @throws ResourceNotFoundException se il ticket non esiste o l'utente non è attivo
     * @throws OptimisticConflictException se la versione non corrisponde
     * @throws BusinessRuleViolationException se il ticket è già chiuso
     */
    public TicketView assignTicket(UUID id, long expectedVersion, UUID operatorId) {
        MaintenanceTicket ticket = loadForUpdate(id, expectedVersion);
        if (!userQueryService.isActiveUser(operatorId)) {
            throw new ResourceNotFoundException("User", operatorId);
        }
        ticket.assign(operatorId);
        return flushAndView(ticket);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    /**
     * Porta il ticket in lavorazione, lo risolve o lo annulla. Gli altri stati non si possono impostare da qui.
     *
     * @param id id del ticket
     * @param expectedVersion versione letta dal client
     * @param status stato di destinazione (IN_PROGRESS, RESOLVED o CANCELLED)
     * @return il ticket aggiornato
     * @throws OptimisticConflictException se la versione non corrisponde
     * @throws BusinessRuleViolationException se la transizione non è ammessa
     */
    public TicketView changeStatus(UUID id, long expectedVersion, TicketStatus status){
        MaintenanceTicket ticket = loadForUpdate(id, expectedVersion);

        switch (status){
            case IN_PROGRESS -> ticket.startWork();
            case RESOLVED -> ticket.resolve(clock.instant());
            case CANCELLED -> ticket.cancel();
            default -> throw new BusinessRuleViolationException("Unsupported status transition to: " + status);
        }
        return flushAndView(ticket);
    }

    /**
     * Carica il ticket e controlla che la sua versione sia quella che il client aveva letto.
     * L'annotazione {@code @Version} di JPA protegge solo l'intervallo fra la lettura e il commit di questa
     * transazione; la versione vista dal client (nell'header If-Match) va confrontata a mano, altrimenti una
     * modifica fatta da altri tra la GET del client e la sua richiesta di modifica andrebbe persa senza errori.
     *
     * @param id id del ticket
     * @param expectedVersion versione attesa
     * @return il ticket caricato
     * @throws ResourceNotFoundException se il ticket non esiste
     * @throws OptimisticConflictException se la versione è diversa (risposta 409)
     */
    private MaintenanceTicket loadForUpdate(UUID id, long expectedVersion) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaintenanceTicket", id));
        if (ticket.getVersion() != expectedVersion) {
            throw new OptimisticConflictException("MaintenanceTicket", id, expectedVersion, ticket.getVersion());
        }
        return ticket;
    }

    /**
     * Scrive le modifiche sul database e costruisce la vista. Hibernate incrementa la versione solo al flush:
     * senza questo passaggio la risposta, e quindi l'ETag, conterrebbe ancora la versione vecchia.
     *
     * @param ticket ticket modificato
     * @return la vista con la versione aggiornata
     */
    private TicketView flushAndView(MaintenanceTicket ticket) {
        maintenanceTicketRepository.flush();
        return TicketView.from(ticket);
    }
}
