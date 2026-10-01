package it.unical.geosentinel.emergency.domain;

import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.AlertAlreadyTakenException;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Niente @Version: ogni modifica passa da un lock pessimistico (findByIdForUpdate), che serializza già le scritture
@Entity
@Table(name = "sos_alerts")
public class SosAlert extends AuditableEntity {
    @Column(name = "hiker_id", nullable = false, updatable = false)
    private UUID hikerId;
    // facoltativa (ADR-0012): un SOS senza chiave non si rifiuta mai
    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;
    @Column(name = "location_updated_at", nullable = false)
    private Instant locationUpdatedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SosStatus status;
    @Column(name = "medical_notes", columnDefinition = "TEXT")
    private String medicalNotes;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_team_id")
    private RescueTeam assignedTeam;
    // chi ha inviato la squadra (audit: updatedBy cambia a ogni aggiornamento di posizione)
    @Column(name = "operator_id")
    private UUID operatorId;
    // chiusura (RESOLVED o CANCELLED): da qui partono i 90 giorni di conservazione della posizione
    @Column(name = "closed_at")
    private Instant closedAt;
    // notified_at e anonymized_at non sono mappati: li scrivono solo le query di SosAlertRepository

    protected SosAlert() {}

    private SosAlert(UUID hikerId, UUID idempotencyKey, Point location, String medicalNotes, Instant now) {
        this.hikerId = Objects.requireNonNull(hikerId, "hikerId cannot be null");
        this.idempotencyKey = idempotencyKey;
        this.location = validateGeometry(location);
        this.locationUpdatedAt = Objects.requireNonNull(now, "now cannot be null");
        this.medicalNotes = medicalNotes;
        this.status = SosStatus.ACTIVE;
    }

    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "Location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("SOS location geometry must have SRID 4326");
        }
        return location;
    }

    public static SosAlert raise(UUID hikerId, UUID idempotencyKey, Point location, String medicalNotes, Instant now) {
        return new SosAlert(hikerId, idempotencyKey, location, medicalNotes, now);
    }

    public boolean isActive() {
        return status == SosStatus.ACTIVE || status == SosStatus.DISPATCHED;
    }

    // locationUpdatedAt dice ai soccorritori quanto è vecchia la posizione
    public void updateLocation(Point newLocation, Instant now) {
        if(!isActive()) {
            throw new BusinessRuleViolationException("Cannot update location for alert in status " + this.status);
        }
        this.location = validateGeometry(newLocation);
        this.locationUpdatedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    public void dispatch(RescueTeam team, UUID operatorId) {
        if(this.status == SosStatus.DISPATCHED) {
            throw new AlertAlreadyTakenException(getId());
        }
        if(this.status != SosStatus.ACTIVE) {
            throw new BusinessRuleViolationException("Cannot dispatch a team to an alert with status " + this.status);
        }
        team.deploy();
        this.assignedTeam = team;
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId cannot be null");
        this.status = SosStatus.DISPATCHED;
    }

    public void resolve(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        if(!isActive()) {
            throw new BusinessRuleViolationException("Cannot resolve alert with status " + this.status);
        }
        if(this.assignedTeam != null) {
            this.assignedTeam.release();
        }
        this.status = SosStatus.RESOLVED;
        this.closedAt = now;
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        if(this.status == SosStatus.RESOLVED) {
            throw new BusinessRuleViolationException("Cannot cancel an already RESOLVED alert");
        }
        if(this.status == SosStatus.CANCELLED) {
            return;
        }
        if(this.assignedTeam != null) {
            this.assignedTeam.release();
            this.assignedTeam = null;
        }
        this.status = SosStatus.CANCELLED;
        this.closedAt = now;
    }

    public UUID getHikerId() { return hikerId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public Point getLocation() { return location; }
    public Instant getLocationUpdatedAt() { return locationUpdatedAt; }
    public SosStatus getStatus() { return status; }
    public String getMedicalNotes() { return medicalNotes; }
    public RescueTeam getAssignedTeam() { return assignedTeam; }
    public UUID getOperatorId() { return operatorId; }
    public Instant getClosedAt() { return closedAt; }
}
