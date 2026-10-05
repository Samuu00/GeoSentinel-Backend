package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.application.dto.HazardCommands.CreateHazardReportCommand;
import it.unical.geosentinel.trail.application.dto.HazardCommands.HazardView;
import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.HazardStatus;
import it.unical.geosentinel.trail.domain.Trail;
import it.unical.geosentinel.trail.infrastructure.persistence.HazardReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HazardReportService {
    private static final double DEFAULT_BUFFER_METERS = 100.0;

    private final HazardReportRepository hazardReportRepository;
    private final TrailService trailService;
    private final GeometryMapper geometryMapper;

    public HazardReportService(HazardReportRepository hazardReportRepository, TrailService trailService, GeometryMapper geometryMapper) {
        this.hazardReportRepository = hazardReportRepository;
        this.trailService = trailService;
        this.geometryMapper = geometryMapper;
    }

    // Una segnalazione REJECTED (falsa o abusiva) la vedono solo il gestore e chi l'ha fatta.
    // authentication.name è il sub del JWT, cioè l'id dell'utente.
    @Transactional(readOnly = true)
    @PostAuthorize("returnObject.status().name() != 'REJECTED' or hasRole('PARK_MANAGER') "
            + "or returnObject.reporterId().toString() == authentication.name")
    public HazardView getHazard(UUID id) {
        HazardReport report = hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
        return HazardView.from(report, geometryMapper);
    }
    // per le altre feature (es. maintenance), che non usano HazardReportRepository. Niente @PostAuthorize:
    // l'entità non esce dall'API, e chi la chiede ha già superato i controlli del proprio service
    @Transactional(readOnly = true)
    public HazardReport getHazardOrThrow(UUID id) {
        return hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
    }
    @Transactional(readOnly = true)
    public List<HazardView> findNearTrail(UUID trailId) {
        trailService.getTrailOrThrow(trailId); // 404 invece di una lista vuota per un sentiero inesistente
        return hazardReportRepository.findActiveNearTrail(trailId, DEFAULT_BUFFER_METERS)
                .stream()
                .map(h -> HazardView.from(h, geometryMapper))
                .toList();
    }

    // Le proprie segnalazioni in ogni stato, anche REJECTED: chi le ha fatte può sempre vederle (come in getHazard).
    // reporterId arriva dal sub del JWT, quindi un utente non può chiedere quelle di un altro
    @Transactional(readOnly = true)
    public Page<HazardView> findMine(UUID reporterId, Pageable pageable) {
        return hazardReportRepository.findByReporterId(reporterId, pageable)
                .map(h -> HazardView.from(h, geometryMapper));
    }

    // reporterId arriva dal sub del JWT: l'utente esiste, e comunque la FK su users lo garantisce
    @Transactional
    @PreAuthorize("hasRole('HIKER')")
    public HazardView create(UUID reporterId, CreateHazardReportCommand cmd) {
        Trail trail = null;
        if(cmd.trailId() != null) {
            trail = trailService.getTrailOrThrow(cmd.trailId());
        }
        HazardReport report = new HazardReport(reporterId, trail, cmd.type(), cmd.severity(), cmd.description(), cmd.location());
        return HazardView.from(hazardReportRepository.save(report), geometryMapper);
    }

    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public HazardView changeStatus(UUID id, HazardStatus status) {
        HazardReport report = hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
        switch (status) {
            case VERIFIED -> report.verify();
            case REJECTED -> report.reject();
            case RESOLVED -> report.resolve();
            // IllegalArgumentException non ha handler e diventerebbe un 500
            case SUBMITTED -> throw new BusinessRuleViolationException("Cannot transition back to SUBMITTED");
        }
        return HazardView.from(report, geometryMapper);
    }
}
