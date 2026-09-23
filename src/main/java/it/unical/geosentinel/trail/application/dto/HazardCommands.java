package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.HazardStatus;
import it.unical.geosentinel.trail.domain.HazardType;
import it.unical.geosentinel.trail.domain.Severity;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

public class HazardCommands {
    public record CreateHazardReportCommand(UUID trailId, HazardType type, Severity severity, String description,  Point location) {}
    public record HazardView(
            UUID id,
            UUID reporterId,
            UUID trailId,
            HazardType type,
            Severity severity,
            String description,
            GeoPointDto location,
            HazardStatus status
        ) {
        public static HazardView from(HazardReport h, GeometryMapper geo) {
            return new HazardView(
                    h.getId(),
                    h.getReporterId(),
                    h.getTrail() != null ? h.getTrail().getId() : null,
                    h.getType(),
                    h.getSeverity(),
                    h.getDescription(),
                    geo.toDto(h.getLocation()),
                    h.getStatus()
            );
        }
    }
}
