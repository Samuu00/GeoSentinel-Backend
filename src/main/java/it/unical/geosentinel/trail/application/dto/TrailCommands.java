package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;
import it.unical.geosentinel.trail.domain.Trail;
import org.locationtech.jts.geom.LineString;

import java.util.UUID;

public class TrailCommands {
    public record CreateTrailCommand(String name, String description, Difficulty difficulty, LineString path) {}
    public record UpdateTrailCommand(String name, String description, Difficulty difficulty, LineString path) {}
    public record GeoQuery(double lon, double lat, double radiusMeters, int limit) {}
    public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
        public BoundingBox {
            if (minLon >= maxLon || minLat >= maxLat
                    || minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
                throw new IllegalArgumentException("Invalid bounding box");
            }
        }
    }
    // Finisce nella cache "trails" come JSON: solo tipi serializzabili, niente geometrie JTS
    public record TrailDetail(
            UUID id,
            String name,
            String description,
            Difficulty difficulty,
            TrailStatus status,
            GeoJsonLineString path,
            Double lengthM,
            Long version
    ) {
        public static TrailDetail from(Trail trail, GeometryMapper geo) {
            return new TrailDetail(
                    trail.getId(),
                    trail.getName(),
                    trail.getDescription(),
                    trail.getDifficulty(),
                    trail.getStatus(),
                    geo.toDto(trail.getPath()),
                    trail.getLengthM(),
                    trail.getVersion()
            );
        }
    }
    public record TrailSummary(UUID id, String name, Difficulty difficulty, TrailStatus status, Double lengthM) {
        public static TrailSummary from(Trail trail) {
            return new TrailSummary(
                    trail.getId(),
                    trail.getName(),
                    trail.getDifficulty(),
                    trail.getStatus(),
                    trail.getLengthM()
            );
        }
    }

}
