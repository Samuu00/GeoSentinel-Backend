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
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.locationtech.jts.geom.Point;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "hazard_reports")
public class HazardReport extends AuditableEntity {
    // Solo l'id: trail non dipende dal dominio di identity (la FK verso users resta nel DDL)
    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private HazardType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HazardStatus status;

    protected HazardReport() {}

    public HazardReport(UUID reporterId, Trail trail, HazardType type, Severity severity, String description, Point location) {
        this.reporterId = Objects.requireNonNull(reporterId, "reporterId cannot be null");
        this.trail = trail;
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.severity = Objects.requireNonNull(severity, "severity cannot be null");
        this.description = description;
        this.location = validateGeometry(location);
        this.status = HazardStatus.SUBMITTED;
    }

    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("Hazard location must have SRID 4326");
        }
        return location;
    }

    public void verify(){
        if(this.status != HazardStatus.SUBMITTED){
            throw new BusinessRuleViolationException(
                    "Cannot verify a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.VERIFIED;
    }

    public void reject(){
        if(this.status != HazardStatus.SUBMITTED){
            throw new BusinessRuleViolationException(
                    "Cannot reject a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.REJECTED;
    }

    public void resolve(){
        if(this.status != HazardStatus.VERIFIED){
            throw new BusinessRuleViolationException(
                    "Cannot resolve a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.RESOLVED;
    }

    public void assignToTrail(Trail trail) {
        this.trail = Objects.requireNonNull(trail, "trail cannot be null");
    }

    public UUID getReporterId() { return reporterId; }
    public Trail getTrail() { return trail; }
    public HazardType getType() { return type; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public Point getLocation() { return location; }
    public HazardStatus getStatus() { return status; }
}
