package it.unical.geosentinel.trail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.locationtech.jts.geom.LineString;

import java.util.Objects;

@Entity
@Table(name = "trails")
public class Trail extends AuditableEntity{
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TrailStatus status;

    @Column(name = "path", nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString path;

    // Colonna GENERATED in Postgres: @Generated la rilegge dopo INSERT e UPDATE, altrimenti resterebbe null
    // dopo la creazione e vecchia dopo un cambio di path (e finirebbe così nella cache "trails")
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "length_m", insertable = false, updatable = false)
    private Double lengthM;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Trail() {}

    public Trail(String name, String description, Difficulty difficulty, LineString path) {
        this.name = Objects.requireNonNull(name, "Trail name cannot be null");
        this.description = description;
        this.difficulty = Objects.requireNonNull(difficulty, "Trail difficulty cannot be null");
        this.status = TrailStatus.OPEN;
        this.path = validatePath(path);
    }

    private static LineString validatePath(LineString path) {
        Objects.requireNonNull(path, "path geometry cannot be null");
        if (path.getSRID() != 4326) {
            throw new IllegalArgumentException("Trail path geometry must have SRID 4326");
        }
        if (path.getNumPoints() < 2) {
            throw new IllegalArgumentException("Trail path requires at least two coordinates");
        }
        return path;
    }

    public void updateDetails(String name, String description, Difficulty difficulty, LineString path) {
        this.name = Objects.requireNonNull(name, "Trail name cannot be null");
        this.description = description;
        this.difficulty = Objects.requireNonNull(difficulty, "Trail difficulty cannot be null");
        this.path = validatePath(path);
    }

    public void changeStatus(TrailStatus status) {
        Objects.requireNonNull(status, "Trail status cannot be null");
        if (this.status == status) return;
        if (this.status == TrailStatus.CLOSED && status == TrailStatus.WARNING) {
            throw new BusinessRuleViolationException("A CLOSED trail cannot be moved directly to WARNING; reopen it first");
        }
        this.status = status;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Difficulty getDifficulty() { return difficulty; }
    public TrailStatus getStatus() { return status; }
    public LineString getPath() { return path; }
    public Double getLengthM() { return lengthM; }
    public Long getVersion() { return version; }
}
