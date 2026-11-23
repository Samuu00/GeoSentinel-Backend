// Service dei sentieri: lettura del dettaglio (con cache Redis), ricerche geografiche attorno a un punto
// o dentro il riquadro della mappa, e operazioni di gestione riservate al gestore del parco (creazione,
// modifica, cambio di stato). Le altre feature che hanno bisogno di un sentiero passano da qui.
package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.config.CacheConfig;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.application.dto.TrailCommands.BoundingBox;
import it.unical.geosentinel.trail.application.dto.TrailCommands.CreateTrailCommand;
import it.unical.geosentinel.trail.application.dto.TrailCommands.GeoQuery;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailDetail;
import it.unical.geosentinel.trail.application.dto.TrailCommands.TrailSummary;
import it.unical.geosentinel.trail.application.dto.TrailCommands.UpdateTrailCommand;
import it.unical.geosentinel.trail.domain.Trail;
import it.unical.geosentinel.trail.domain.TrailStatus;
import it.unical.geosentinel.trail.infrastructure.persistence.TrailRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Logica applicativa dei sentieri. I metodi di scrittura sono protetti con {@code @PreAuthorize} e
 * invalidano la voce della cache del sentiero modificato.
 */
@Service
public class TrailService {
    /** Raggio massimo delle ricerche geografiche, in metri (50 km); usato anche da POI e pericoli. */
    static final double MAX_SEARCH_RADIUS_METERS = 50_000.0;
    /** Numero massimo di risultati delle ricerche attorno a un punto. */
    static final int MAX_RESULTS = 50;
    /**
     * Numero massimo di sentieri restituiti per un riquadro della mappa. Il valore è scelto perché tutti i
     * sentieri della Calabria (circa 350) stiano in una sola richiesta, così la mappa li mostra subito anche
     * quando è molto rimpicciolita.
     */
    private static final int MAX_BBOX_RESULTS = 500;

    private final TrailRepository trailRepository;
    private final GeometryMapper geometryMapper;

    /**
     * @param trailRepository repository dei sentieri
     * @param geometryMapper  conversione delle geometrie nei DTO
     */
    public TrailService(TrailRepository trailRepository, GeometryMapper geometryMapper) {
        this.trailRepository = trailRepository;
        this.geometryMapper = geometryMapper;
    }

    /**
     * Restituisce il dettaglio di un sentiero. Il risultato è salvato nella cache dei sentieri con l'id come
     * chiave, e le modifiche lo invalidano.
     *
     * @param id identificativo del sentiero
     * @return il dettaglio
     * @throws ResourceNotFoundException se il sentiero non esiste
     */
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.TRAILS_CACHE, key = "#id")
    public TrailDetail getTrail(UUID id) {
        return TrailDetail.from(getTrailOrThrow(id), geometryMapper);
    }
    /**
     * Restituisce l'entità del sentiero. Serve agli altri service (POI, pericoli, manutenzione), che non
     * possono usare direttamente il repository di questa feature.
     *
     * @param id identificativo del sentiero
     * @return l'entità
     * @throws ResourceNotFoundException se il sentiero non esiste
     */
    @Transactional(readOnly = true)
    public Trail getTrailOrThrow(UUID id){
        return trailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trail", id));
    }
    /**
     * Cerca i sentieri entro un raggio da un punto, dal più vicino. Raggio e numero di risultati vengono
     * ricondotti ai limiti massimi del service anche se il chiamante chiede di più.
     *
     * @param query centro, raggio e numero di risultati
     * @return i riepiloghi dei sentieri trovati
     */
    @Transactional(readOnly = true)
    public List<TrailSummary> findNearby(GeoQuery query) {
        double radius = Math.min(query.radiusMeters(), MAX_SEARCH_RADIUS_METERS);
        int limit = Math.max(1, Math.min(query.limit(), MAX_RESULTS));
        return trailRepository.findWithinDistance(query.lon(), query.lat(), radius, limit)
                .stream()
                .map(trail -> TrailSummary.from(trail, geometryMapper))
                .toList();
    }
    /**
     * Cerca i sentieri che attraversano il riquadro visibile della mappa, fino a {@link #MAX_BBOX_RESULTS}.
     *
     * @param box riquadro della mappa
     * @return i riepiloghi dei sentieri, ordinati per id così che la risposta sia stabile
     */
    @Transactional(readOnly = true)
    public List<TrailSummary> findInBoundingBox(BoundingBox box){
        return trailRepository.findIntersecting(box.minLon(), box.minLat(), box.maxLon(), box.maxLat(), MAX_BBOX_RESULTS)
                .stream()
                .map(trail -> TrailSummary.from(trail, geometryMapper))
                .toList();
    }

    /**
     * Crea un nuovo sentiero. Si usa {@code saveAndFlush} prima di costruire il DTO perché length_m
     * (colonna generata) e version vengono valorizzati solo quando l'SQL arriva al database: senza il flush
     * la risposta avrebbe una lunghezza nulla e una versione non aggiornata.
     *
     * @param cmd dati del sentiero
     * @return il dettaglio del sentiero creato
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public TrailDetail create(CreateTrailCommand cmd) {
        Trail trail = new Trail(cmd.name(), cmd.description(), cmd.difficulty(), cmd.path());
        return TrailDetail.from(trailRepository.saveAndFlush(trail), geometryMapper);
    }

    /**
     * Modifica i dati e il tracciato di un sentiero e invalida la sua voce di cache. Anche qui il flush
     * esplicito aggiorna lunghezza e versione prima di costruire la risposta.
     *
     * @param id  identificativo del sentiero
     * @param cmd nuovi dati
     * @return il dettaglio aggiornato
     * @throws ResourceNotFoundException se il sentiero non esiste
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    @CacheEvict(value = CacheConfig.TRAILS_CACHE, key = "#id")
    public TrailDetail update(UUID id, UpdateTrailCommand cmd) {
        Trail trail = getTrailOrThrow(id);
        trail.updateDetails(cmd.name(), cmd.description(), cmd.difficulty(), cmd.path());
        trailRepository.flush();
        return TrailDetail.from(trail, geometryMapper);
    }

    /**
     * Cambia lo stato di percorribilità di un sentiero e invalida la sua voce di cache.
     *
     * @param id     identificativo del sentiero
     * @param status nuovo stato
     * @return il dettaglio aggiornato
     * @throws ResourceNotFoundException se il sentiero non esiste
     * @throws it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException se la transizione
     *         non è ammessa
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    @CacheEvict(value = CacheConfig.TRAILS_CACHE, key = "#id")
    public TrailDetail changeStatus(UUID id, TrailStatus status) {
        Trail trail = getTrailOrThrow(id);
        trail.changeStatus(status);
        trailRepository.flush();
        return TrailDetail.from(trail, geometryMapper);
    }
}
