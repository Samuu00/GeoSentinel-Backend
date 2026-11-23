// Service dei punti di interesse: dettaglio, ricerca attorno a un punto con filtro facoltativo per
// categoria, elenco dei POI di un sentiero e creazione, riservata al gestore del parco.
package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.PoiType;
import it.unical.geosentinel.trail.application.dto.PoiCommands.CreatePoiCommand;
import it.unical.geosentinel.trail.application.dto.PoiCommands.PoiView;
import it.unical.geosentinel.trail.domain.Poi;
import it.unical.geosentinel.trail.domain.Trail;
import it.unical.geosentinel.trail.infrastructure.persistence.PoiRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Logica applicativa dei POI. Per verificare l'esistenza dei sentieri passa da {@link TrailService}.
 */
@Service
public class PoiService {
    private final PoiRepository poiRepository;
    private final TrailService trailService;
    private final GeometryMapper geometryMapper;

    /**
     * @param poiRepository  repository dei POI
     * @param trailService   service dei sentieri
     * @param geometryMapper conversione delle geometrie nei DTO
     */
    public PoiService(PoiRepository poiRepository, TrailService trailService, GeometryMapper geometryMapper) {
        this.poiRepository = poiRepository;
        this.trailService = trailService;
        this.geometryMapper = geometryMapper;
    }
    /**
     * @param id identificativo del POI
     * @return la vista del POI
     * @throws ResourceNotFoundException se il POI non esiste
     */
    @Transactional(readOnly = true)
    public PoiView getPoi(UUID id) {
        Poi poi = poiRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Poi", id));
        return PoiView.from(poi, geometryMapper);
    }
    /**
     * Numero massimo di POI per richiesta. La mappa chiede i POI dell'area visibile e l'app nasconde i
     * segnaposto che si sovrappongono, quindi si può restituirne parecchi senza affollare lo schermo.
     */
    static final int MAX_POI_RESULTS = 300;

    /**
     * Cerca i POI entro un raggio da un punto, dal più vicino, con un filtro facoltativo sulla categoria.
     * Raggio e numero di risultati vengono ricondotti ai massimi consentiti.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param type         categoria richiesta, oppure {@code null} per tutte
     * @param limit        numero massimo di risultati richiesto
     * @return le viste dei POI trovati
     */
    @Transactional(readOnly = true)
    public List<PoiView> findNearby(double lon, double lat, double radiusMeters, PoiType type, int limit) {
        double radius = Math.min(radiusMeters, TrailService.MAX_SEARCH_RADIUS_METERS);
        int boundedLimit = Math.max(1, Math.min(limit, MAX_POI_RESULTS));
        return poiRepository.findNearby(lon, lat, radius, type != null ? type.name() : null, boundedLimit)
                .stream()
                .map(poi -> PoiView.from(poi, geometryMapper))
                .toList();
    }
    /**
     * @param trailId identificativo del sentiero
     * @return i POI collegati al sentiero
     * @throws ResourceNotFoundException se il sentiero non esiste
     */
    @Transactional(readOnly = true)
    public List<PoiView> findByTrail(UUID trailId) {
        // Per un sentiero inesistente si risponde 404 invece di una lista vuota, come per le segnalazioni.
        trailService.getTrailOrThrow(trailId);
        return poiRepository.findByTrailId(trailId)
                .stream()
                .map(poi -> PoiView.from(poi, geometryMapper))
                .toList();
    }
    /**
     * Crea un POI, collegato a un sentiero se il comando ne indica uno, altrimenti indipendente.
     *
     * @param cmd dati del POI
     * @return la vista del POI creato
     * @throws ResourceNotFoundException se il sentiero indicato non esiste
     */
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public PoiView create(CreatePoiCommand cmd) {
        Poi poi;
        if(cmd.trailId() != null) {
            Trail trail = trailService.getTrailOrThrow(cmd.trailId());
            poi = Poi.createForTrail(trail, cmd.name(), cmd.type(), cmd.location());
        } else {
            poi = Poi.create(cmd.name(), cmd.type(), cmd.location());
        }
        return PoiView.from(poiRepository.save(poi), geometryMapper);
    }
}
