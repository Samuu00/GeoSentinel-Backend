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

@Service
public class TrailService {
    static final double MAX_SEARCH_RADIUS_METERS = 50_000.0; // 50 km max
    static final int MAX_RESULTS = 50;
    private static final int MAX_BBOX_RESULTS = 200;

    private final TrailRepository trailRepository;
    private final GeometryMapper geometryMapper;

    public TrailService(TrailRepository trailRepository, GeometryMapper geometryMapper) {
        this.trailRepository = trailRepository;
        this.geometryMapper = geometryMapper;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.TRAILS_CACHE, key = "#id")
    public TrailDetail getTrail(UUID id) {
        return TrailDetail.from(getTrailOrThrow(id), geometryMapper);
    }
    @Transactional(readOnly = true)
    public Trail getTrailOrThrow(UUID id){
        return trailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trail", id));
    }
    @Transactional(readOnly = true)
    public List<TrailSummary> findNearby(GeoQuery query) {
        double radius = Math.min(query.radiusMeters(), MAX_SEARCH_RADIUS_METERS);
        int limit = Math.max(1, Math.min(query.limit(), MAX_RESULTS));
        return trailRepository.findWithinDistance(query.lon(), query.lat(), radius, limit)
                .stream()
                .map(TrailSummary::from)
                .toList();
    }
    @Transactional(readOnly = true)
    public List<TrailSummary> findInBoundingBox(BoundingBox box){
        return trailRepository.findContainedIn(box.minLon(), box.minLat(), box.maxLon(), box.maxLat(), MAX_BBOX_RESULTS)
                .stream()
                .map(TrailSummary::from)
                .toList();
    }

    // Flush prima di costruire il DTO: length_m (colonna generata) e version si aggiornano solo con l'SQL.
    // Senza, la risposta avrebbe lengthM null/vecchio e la version precedente (ETag sbagliato).
    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    public TrailDetail create(CreateTrailCommand cmd) {
        Trail trail = new Trail(cmd.name(), cmd.description(), cmd.difficulty(), cmd.path());
        return TrailDetail.from(trailRepository.saveAndFlush(trail), geometryMapper);
    }

    @Transactional
    @PreAuthorize("hasRole('PARK_MANAGER')")
    @CacheEvict(value = CacheConfig.TRAILS_CACHE, key = "#id")
    public TrailDetail update(UUID id, UpdateTrailCommand cmd) {
        Trail trail = getTrailOrThrow(id);
        trail.updateDetails(cmd.name(), cmd.description(), cmd.difficulty(), cmd.path());
        trailRepository.flush();
        return TrailDetail.from(trail, geometryMapper);
    }

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
