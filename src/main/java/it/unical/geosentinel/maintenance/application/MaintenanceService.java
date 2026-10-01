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

// La cache per sentiero si svuota dopo ogni modifica (#result.trailId: il sentiero di un ticket non cambia).
// I ticket di un solo rifugio non hanno sentiero e non sono in cache.
@Service
@PreAuthorize("hasRole('PARK_MANAGER')")
public class MaintenanceService {
    private static final String EVICT_KEY = "#result.trailId()";
    private static final String EVICT_IF_TRAIL = "#result.trailId() != null";

    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final TrailService trailService;
    private final RefugeBookingService refugeBookingService;
    private final HazardReportService hazardReportService;
    private final UserQueryService userQueryService;
    private final Clock clock;

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

    @Transactional(readOnly = true)
    public TicketView getTicket(UUID id) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaintenanceTicket", id));
        return TicketView.from(ticket);
    }

    @Transactional(readOnly = true)
    public Page<TicketView> findTickets(TicketQuery query, Pageable pageable){
        return maintenanceTicketRepository.searchTickets(query.status(), query.priority(), query.assignedTo(), pageable)
                .map(TicketView::from);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = "#trailId")
    public List<TicketView> byTrail(UUID trailId) {
        trailService.getTrailOrThrow(trailId); // 404 invece di una lista vuota per un sentiero inesistente
        return maintenanceTicketRepository.findByTrailIdOrderByCreatedAtDesc(trailId).stream()
                .map(TicketView::from)
                .toList();
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    public TicketView createTicket(CreateMaintenanceTicketCommand cmd){
        // un solo target: con più id gli altri verrebbero ignorati in silenzio
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

        // saveAndFlush: id, created_at e version valorizzati prima del DTO (la version finisce nell'ETag)
        return TicketView.from(maintenanceTicketRepository.saveAndFlush(ticket));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
    public TicketView updateDetails(UUID id, long expectedVersion, UpdateTicketCommand cmd) {
        MaintenanceTicket ticket = loadForUpdate(id, expectedVersion);
        ticket.updateDetails(cmd.title(), cmd.description(), cmd.priority());
        return flushAndView(ticket);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.MAINTENANCE_BY_TRAIL_CACHE, key = EVICT_KEY, condition = EVICT_IF_TRAIL)
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

    // @Version protegge solo la finestra load->commit: la versione letta dal client (If-Match) va confrontata a mano,
    // altrimenti il lost update tra la sua GET e la sua PATCH non viene rilevato
    private MaintenanceTicket loadForUpdate(UUID id, long expectedVersion) {
        MaintenanceTicket ticket = maintenanceTicketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaintenanceTicket", id));
        if (ticket.getVersion() != expectedVersion) {
            throw new OptimisticConflictException("MaintenanceTicket", id, expectedVersion, ticket.getVersion());
        }
        return ticket;
    }

    // la version si incrementa solo al flush: senza, la risposta (e l'ETag) avrebbe quella vecchia
    private TicketView flushAndView(MaintenanceTicket ticket) {
        maintenanceTicketRepository.flush();
        return TicketView.from(ticket);
    }
}
