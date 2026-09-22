package it.unical.geosentinel.trail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import it.unical.geosentinel.shared.domain.AuditableEntity;
import org.locationtech.jts.geom.Point;

import java.util.Objects;

@Entity
@Table(name = "pois")
public class Poi extends AuditableEntity{
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PoiType type;

    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    protected Poi() {}

    private Poi(Trail trail, String name, PoiType type, Point location) {
        this.trail = trail;
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.location = validateGeometry(location);
    }

    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("POI location must have SRID 4326");
        }
        return location;
    }

    // POI indipendente da un sentiero (es. un bivacco raggiungibile da più sentieri)
    public static Poi create(String name, PoiType type, Point location) {
        return new Poi(null, name, type, location);
    }

    public static Poi createForTrail(Trail trail, String name, PoiType type, Point location) {
        return new Poi(Objects.requireNonNull(trail, "trail cannot be null"), name, type, location);
    }

    public void update(String name, PoiType type, Point location) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.location = validateGeometry(location);
    }

    public void assignToTrail(Trail trail) {
        this.trail = Objects.requireNonNull(trail, "trail cannot be null");
    }

    public void detachFromTrail() {
        this.trail = null;
    }

    public Trail getTrail() { return trail; }
    public String getName() { return name; }
    public PoiType getType() { return type; }
    public Point getLocation() { return location; }
}
