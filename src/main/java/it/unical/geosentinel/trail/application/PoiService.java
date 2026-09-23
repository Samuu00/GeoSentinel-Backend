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

@Service
public class PoiService {
    private final PoiRepository poiRepository;
    private final TrailService trailService;
    private final GeometryMapper geometryMapper;

    public PoiService(PoiRepository poiRepository, TrailService trailService, GeometryMapper geometryMapper) {
        this.poiRepository = poiRepository;
        this.trailService = trailService;
        this.geometryMapper = geometryMapper;
    }
    @Transactional(readOnly = true)
    public PoiView getPoi(UUID id) {
        Poi poi = poiRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Poi", id));
        return PoiView.from(poi, geometryMapper);
    }
    @Transactional(readOnly = true)
    public List<PoiView> findNearby(double lon, double lat, double radiusMeters, PoiType type, int limit) {
        double radius = Math.min(radiusMeters, TrailService.MAX_SEARCH_RADIUS_METERS);
        int boundedLimit = Math.max(1, Math.min(limit, TrailService.MAX_RESULTS));
        return poiRepository.findNearby(lon, lat, radius, type != null ? type.name() : null, boundedLimit)
                .stream()
                .map(poi -> PoiView.from(poi, geometryMapper))
                .toList();
    }
    @Transactional(readOnly = true)
    public List<PoiView> findByTrail(UUID trailId) {
        return poiRepository.findByTrailId(trailId)
                .stream()
                .map(poi -> PoiView.from(poi, geometryMapper))
                .toList();
    }
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
