package it.unical.geosentinel.emergency.application;

import it.unical.geosentinel.emergency.application.dto.SosCommands.CreateRescueTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.DispatchTeamCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RaiseSosCommand;
import it.unical.geosentinel.emergency.application.dto.SosCommands.RescueTeamView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosAlertView;
import it.unical.geosentinel.emergency.application.dto.SosCommands.SosRaiseResult;
import it.unical.geosentinel.emergency.application.dto.SosCommands.UpdateLocationCommand;
import it.unical.geosentinel.emergency.domain.RescueTeam;
import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.domain.SosAlertStatusChangedEvent;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.infrastructure.persistence.RescueTeamRepository;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SosService {
    private static final List<SosStatus> ACTIVE_STATUSES = List.of(SosStatus.ACTIVE, SosStatus.DISPATCHED);
    private static final double MAX_RADIUS_METERS = 100_000;

    private final SosAlertRepository sosAlertRepository;
    private final RescueTeamRepository rescueTeamRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final GeometryMapper geometryMapper;
    private final Clock clock;

    public SosService(SosAlertRepository sosAlertRepository, RescueTeamRepository rescueTeamRepository,
                      ApplicationEventPublisher applicationEventPublisher, GeometryMapper geometryMapper, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.rescueTeamRepository = rescueTeamRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.geometryMapper = geometryMapper;
        this.clock = clock;
    }

    // Un SOS non si rifiuta mai (ADR-0012): un retry con la stessa chiave (facoltativa, può essere null), o un secondo SOS
    // mentre il primo è attivo, restituisce l'allerta esistente (created = false) invece di un errore o di un duplicato
    @Transactional
    @PreAuthorize("hasRole('HIKER')")
    public SosRaiseResult raiseSos(UUID hikerId, UUID idempotencyKey, RaiseSosCommand cmd) {
        sosAlertRepository.lockHiker(hikerId.getMostSignificantBits() ^ hikerId.getLeastSignificantBits());

        Optional<SosAlert> existing = Optional.ofNullable(idempotencyKey)
                .flatMap(key -> sosAlertRepository.findByHikerIdAndIdempotencyKey(hikerId, key))
                .or(() -> sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES));
        if (existing.isPresent()) {
            return new SosRaiseResult(SosAlertView.from(existing.get(), geometryMapper), false);
        }

        SosAlert saved = sosAlertRepository.save(
                SosAlert.raise(hikerId, idempotencyKey, cmd.location(), cmd.medicalNotes(), clock.instant()));

        applicationEventPublisher.publishEvent(new SosAlertRaisedEvent(
                saved.getId(),
                saved.getLocation().getX(),
                saved.getLocation().getY(),
                saved.getCreatedAt()
        ));
        return new SosRaiseResult(SosAlertView.from(saved, geometryMapper), true);
    }

    // Cancellazione dell'account. Stesso advisory lock di raiseSos: un SOS lanciato in parallelo non sfugge.
    // Poi l'ordine fisso alert -> team, come in cancelSos
    @Transactional
    public void cancelActiveOf(UUID hikerId) {
        sosAlertRepository.lockHiker(hikerId.getMostSignificantBits() ^ hikerId.getLeastSignificantBits());
        sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES)
                .flatMap(active -> sosAlertRepository.findByIdForUpdate(active.getId()))
                .ifPresent(alert -> {
                    lockAssignedTeam(alert);
                    alert.cancel(clock.instant());
                });
    }

    @Transactional
    public SosAlertView updateLiveLocation(UUID alertId, UUID hikerId, UpdateLocationCommand cmd) {
        SosAlert alert = sosAlertRepository.findByIdAndHikerIdForUpdate(alertId, hikerId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS alert", alertId));
        alert.updateLocation(cmd.newLocation(), clock.instant());
        return SosAlertView.from(alert, geometryMapper);
    }

    @Transactional
    public void cancelSos(UUID alertId, UUID hikerId) {
        SosAlert alert = sosAlertRepository.findByIdAndHikerIdForUpdate(alertId, hikerId)
                .orElseThrow(() -> new ResourceNotFoundException("SOS alert", alertId));
        lockAssignedTeam(alert);
        alert.cancel(clock.instant());
    }

    // l'escursionista legge la propria allerta (la Location del POST punta qui), gli operatori tutte
    @Transactional(readOnly = true)
    @PostAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN') or returnObject.hikerId().toString() == authentication.name")
    public SosAlertView getAlert(UUID alertId) {
        SosAlert alert = sosAlertRepository.findByIdWithDetails(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        return SosAlertView.from(alert, geometryMapper);
    }

    @Transactional(readOnly = true)
    public SosAlertView getActiveAlertForHiker(UUID hikerId) {
        return sosAlertRepository.findFirstByHikerIdAndStatusIn(hikerId, ACTIVE_STATUSES)
                .map(alert -> SosAlertView.from(alert, geometryMapper))
                .orElseThrow(() -> new ResourceNotFoundException("Active SosAlert for hiker", hikerId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public Page<SosAlertView> findActiveAlerts(Pageable pageable) {
        return sosAlertRepository.findByStatusIn(ACTIVE_STATUSES, pageable)
                .map(alert -> SosAlertView.from(alert, geometryMapper));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public List<SosAlertView> findActiveNearby(double lon, double lat, double radiusMeters, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return sosAlertRepository.findNearbyActive(lon, lat, Math.min(radiusMeters, MAX_RADIUS_METERS), boundedLimit)
                .stream()
                .map(alert -> SosAlertView.from(alert, geometryMapper))
                .toList();
    }

    // ordine fisso dei lock: prima l'allerta, poi la squadra (niente deadlock con resolve/cancel)
    @Transactional
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public SosAlertView dispatchTeam(UUID operatorId, UUID alertId, DispatchTeamCommand cmd) {
        SosAlert alert = sosAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        RescueTeam team = rescueTeamRepository.findByIdForUpdate(cmd.teamId())
                .orElseThrow(() -> new ResourceNotFoundException("RescueTeam", cmd.teamId()));

        alert.dispatch(team, operatorId);
        applicationEventPublisher.publishEvent(new SosAlertStatusChangedEvent(alert.getId(), alert.getHikerId(), alert.getStatus()));
        return SosAlertView.from(alert, geometryMapper);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public SosAlertView resolveAlert(UUID alertId) {
        SosAlert alert = sosAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("SosAlert", alertId));
        lockAssignedTeam(alert);

        alert.resolve(clock.instant());
        applicationEventPublisher.publishEvent(new SosAlertStatusChangedEvent(alert.getId(), alert.getHikerId(), alert.getStatus()));
        return SosAlertView.from(alert, geometryMapper);
    }

    // anagrafica delle squadre: la gestisce l'amministratore, gli operatori le usano
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RescueTeamView createTeam(CreateRescueTeamCommand cmd){
        if(rescueTeamRepository.existsByNameIgnoreCase(cmd.name())){
            throw new BusinessRuleViolationException("A rescue team with name '" + cmd.name() + "' already exists");
        }

        RescueTeam team = new RescueTeam(
                cmd.name(),
                cmd.callSign(),
                cmd.baseLocation(),
                cmd.contactNumber()
        );
        return RescueTeamView.from(rescueTeamRepository.save(team), geometryMapper);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public RescueTeamView getTeam(UUID teamId) {
        return rescueTeamRepository.findById(teamId)
                .map(team -> RescueTeamView.from(team, geometryMapper))
                .orElseThrow(() -> new ResourceNotFoundException("RescueTeam", teamId));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESCUE_OPERATOR', 'ADMIN')")
    public List<RescueTeamView> findAvailableTeamsNearby(double lon, double lat, double radiusMeters, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 30));
        return rescueTeamRepository.findAvailableNearby(lon, lat, radiusMeters, boundedLimit)
                .stream()
                .map(team -> RescueTeamView.from(team, geometryMapper))
                .toList();
    }

    // la squadra si libera chiudendo l'allerta: va bloccata anche lei, sempre dopo l'allerta.
    // getId() sul proxy lazy non lo inizializza; la query con lock carica lo stato aggiornato
    private void lockAssignedTeam(SosAlert alert) {
        if (alert.getAssignedTeam() != null) {
            rescueTeamRepository.findByIdForUpdate(alert.getAssignedTeam().getId());
        }
    }
}
