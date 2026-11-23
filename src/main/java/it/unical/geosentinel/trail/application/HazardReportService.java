// Service delle segnalazioni di pericolo. Gli escursionisti le creano, il gestore del parco le verifica,
// le respinge o le chiude. Qui si decide anche chi può vedere cosa: una segnalazione respinta, ad esempio,
// è visibile solo al gestore e a chi l'ha inviata.
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

/**
 * Logica applicativa delle segnalazioni di pericolo, con i controlli di autorizzazione su ruolo e autore.
 */
@Service
public class HazardReportService {
    /** Distanza in metri entro cui una segnalazione si considera lungo un sentiero. */
    private static final double DEFAULT_BUFFER_METERS = 100.0;

    private final HazardReportRepository hazardReportRepository;
    private final TrailService trailService;
    private final GeometryMapper geometryMapper;

    /**
     * @param hazardReportRepository repository delle segnalazioni
     * @param trailService           service dei sentieri
     * @param geometryMapper         conversione delle geometrie nei DTO
     */
    public HazardReportService(HazardReportRepository hazardReportRepository, TrailService trailService, GeometryMapper geometryMapper) {
        this.hazardReportRepository = hazardReportRepository;
        this.trailService = trailService;
        this.geometryMapper = geometryMapper;
    }

    /**
     * Restituisce una segnalazione. Il controllo {@code @PostAuthorize} avviene sul risultato: una
     * segnalazione {@code REJECTED} (falsa o abusiva) la vedono solo il gestore e chi l'ha inviata. Nella
     * espressione {@code authentication.name} è il subject del JWT, cioè l'id dell'utente.
     *
     * @param id identificativo della segnalazione
     * @return la vista della segnalazione
     * @throws ResourceNotFoundException se la segnalazione non esiste
     */
    @Transactional(readOnly = true)
    @PostAuthorize("returnObject.status().name() != 'REJECTED' or hasRole('PARK_MANAGER') "
            + "or returnObject.reporterId().toString() == authentication.name")
    public HazardView getHazard(UUID id) {
        HazardReport report = hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
        return HazardView.from(report, geometryMapper);
    }
    /**
     * Restituisce l'entità della segnalazione per le altre feature (ad esempio la manutenzione), che non
     * usano il repository di questa. Non c'è {@code @PostAuthorize}: l'entità non esce dall'API e chi la
     * chiede ha già superato i controlli del proprio service.
     *
     * @param id identificativo della segnalazione
     * @return l'entità
     * @throws ResourceNotFoundException se la segnalazione non esiste
     */
    @Transactional(readOnly = true)
    public HazardReport getHazardOrThrow(UUID id) {
        return hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
    }
    /**
     * Elenca le segnalazioni attive entro {@link #DEFAULT_BUFFER_METERS} da un sentiero, dalla più recente:
     * quelle verificate di tutti, più quelle ancora da verificare del richiedente (tutte, se è un gestore).
     *
     * @param trailId                identificativo del sentiero
     * @param requesterId            id dell'utente autenticato che fa la richiesta
     * @param requesterIsParkManager {@code true} se chi chiede è un gestore del parco
     * @return le segnalazioni, al massimo {@code TrailService.MAX_RESULTS}
     * @throws ResourceNotFoundException se il sentiero non esiste
     */
    @Transactional(readOnly = true)
    public List<HazardView> findNearTrail(UUID trailId, UUID requesterId, boolean requesterIsParkManager) {
        // Per un sentiero inesistente si risponde 404 invece di una lista vuota.
        trailService.getTrailOrThrow(trailId);
        return hazardReportRepository.findActiveNearTrail(trailId, DEFAULT_BUFFER_METERS, requesterId,
                        requesterIsParkManager, TrailService.MAX_RESULTS)
                .stream()
                .map(h -> HazardView.from(h, geometryMapper))
                .toList();
    }

    /**
     * Elenca le segnalazioni di un utente in ogni stato, anche {@code REJECTED}: chi le ha inviate può
     * sempre vederle, come in {@link #getHazard}. L'id arriva dal subject del JWT, quindi un utente non può
     * chiedere le segnalazioni di un altro.
     *
     * @param reporterId id dell'utente autenticato
     * @param pageable   paginazione e ordinamento
     * @return la pagina di segnalazioni
     */
    @Transactional(readOnly = true)
    public Page<HazardView> findMine(UUID reporterId, Pageable pageable) {
        return hazardReportRepository.findByReporterId(reporterId, pageable)
                .map(h -> HazardView.from(h, geometryMapper));
    }

    /**
     * Elenca tutte le segnalazioni da verificare ({@code SUBMITTED}), ovunque si trovino. La ricerca per
     * sentiero vede solo quelle entro 100 m da un tracciato: senza questa coda una segnalazione inviata dalla
     * mappa lontano dai sentieri non arriverebbe mai a un gestore.
     *
     * @param pageable paginazione e ordinamento
     * @return la pagina di segnalazioni in attesa
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public Page<HazardView> findPending(Pageable pageable) {
        return hazardReportRepository.findByStatus(HazardStatus.SUBMITTED, pageable)
                .map(h -> HazardView.from(h, geometryMapper));
    }

    /**
     * Elenca i pericoli attivi ({@code VERIFIED}) di tutti gli utenti attorno a un punto, dal più vicino.
     * Il raggio è limitato a {@code TrailService.MAX_SEARCH_RADIUS_METERS} e il numero di risultati a
     * {@code TrailService.MAX_RESULTS}.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit        numero massimo di risultati richiesto
     * @return le segnalazioni trovate
     */
    @Transactional(readOnly = true)
    public List<HazardView> findNearby(double lon, double lat, double radiusMeters, int limit) {
        double radius = Math.min(radiusMeters, TrailService.MAX_SEARCH_RADIUS_METERS);
        int boundedLimit = Math.max(1, Math.min(limit, TrailService.MAX_RESULTS));
        return hazardReportRepository.findActiveNearby(lon, lat, radius, boundedLimit)
                .stream()
                .map(h -> HazardView.from(h, geometryMapper))
                .toList();
    }

    /**
     * Crea una nuova segnalazione nello stato {@code SUBMITTED}. L'id dell'autore arriva dal subject del
     * JWT, quindi l'utente esiste; in ogni caso lo garantisce anche la chiave esterna verso users.
     *
     * @param reporterId id dell'utente autenticato
     * @param cmd        dati della segnalazione
     * @return la vista della segnalazione creata
     * @throws ResourceNotFoundException se il sentiero indicato non esiste
     */
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

    /**
     * Applica una transizione di stato decisa dal gestore del parco.
     *
     * @param id     identificativo della segnalazione
     * @param status stato di destinazione
     * @return la vista aggiornata
     * @throws ResourceNotFoundException      se la segnalazione non esiste
     * @throws BusinessRuleViolationException se la transizione non è ammessa, compreso il ritorno a
     *         {@code SUBMITTED}
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public HazardView changeStatus(UUID id, HazardStatus status) {
        HazardReport report = hazardReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hazard", id));
        switch (status) {
            case VERIFIED -> report.verify();
            case REJECTED -> report.reject();
            case RESOLVED -> report.resolve();
            // Si usa BusinessRuleViolationException (422): una IllegalArgumentException non ha un handler
            // dedicato e diventerebbe un 500.
            case SUBMITTED -> throw new BusinessRuleViolationException("Cannot transition back to SUBMITTED");
        }
        return HazardView.from(report, geometryMapper);
    }
}
