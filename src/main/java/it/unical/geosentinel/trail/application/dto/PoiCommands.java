package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.Poi;
import it.unical.geosentinel.trail.domain.PoiType;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

public class PoiCommands {
    public record CreatePoiCommand(UUID trailId, String name, PoiType type, Point location) {}
    public record PoiView(UUID id, UUID trailId, String name, PoiType type, GeoPointDto location) {
        public static PoiView from(Poi poi, GeometryMapper geo) {
            return new PoiView(poi.getId(), poi.getTrail() != null ? poi.getTrail().getId() : null, poi.getName(), poi.getType(), geo.toDto(poi.getLocation()));
        }
    }
}
