package it.unical.geosentinel.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import it.unical.geosentinel.refuge.domain.Refuge;
import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.Trail;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "maintenance_tickets")
public class MaintenanceTicket extends AuditableEntity {
    @Column(name = "title", nullable = false, length = 150)
    private String title;
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private TicketPriority priority;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TicketStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refuge_id")
    private Refuge refuge;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hazard_report_id")
    private HazardReport hazardReport;
    @Column(name = "assigned_to")
    private UUID assignedTo;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected MaintenanceTicket() {}

    private MaintenanceTicket(String title, String description, TicketPriority priority, Trail trail, Refuge refuge, HazardReport hazardReport) {
        this.title = Objects.requireNonNull(title, "title cannot be null");
        this.description = description;
        this.priority = Objects.requireNonNull(priority, "priority cannot be null");
        this.status = TicketStatus.OPEN;
        this.trail = trail;
        this.refuge = refuge;
        this.hazardReport = hazardReport;
        validateTargetAssociation();
    }

    private void validateTargetAssociation() {
        if (trail == null && refuge == null && hazardReport == null) {
            throw new BusinessRuleViolationException("At least one target association must be provided");
        }
    }

    public static MaintenanceTicket forTrail(String title, String description, TicketPriority priority, Trail trail) {
        return new MaintenanceTicket(title, description, priority, Objects.requireNonNull(trail, "trail cannot be null"), null, null);
    }

    public static MaintenanceTicket forRefuge(String title, String description, TicketPriority priority, Refuge refuge) {
        return new MaintenanceTicket(title, description, priority, null, Objects.requireNonNull(refuge, "refuge cannot be null"), null);
    }

    // il sentiero si eredita dalla segnalazione (può essere null), così il ticket finisce anche nella cache per sentiero
    public static MaintenanceTicket forHazard(String title, String description, TicketPriority priority, HazardReport hazardReport){
        Objects.requireNonNull(hazardReport, "hazardReport cannot be null");
        return new MaintenanceTicket(title, description, priority, hazardReport.getTrail(), null, hazardReport);
    }

    public void assign(UUID operatorId){
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        if(this.status == TicketStatus.RESOLVED || this.status == TicketStatus.CANCELLED){
            throw new BusinessRuleViolationException("Cannot assign a ticket that is already " + this.status);
        }
        this.assignedTo = operatorId;
        // una riassegnazione di un ticket già IN_PROGRESS non lo riporta indietro
        if (this.status == TicketStatus.OPEN) {
            this.status = TicketStatus.ASSIGNED;
        }
    }

    public void startWork(){
        if(this.status != TicketStatus.ASSIGNED && this.status != TicketStatus.OPEN){
            throw new BusinessRuleViolationException("Cannot start work on a ticket with status " + this.status);
        }
        this.status = TicketStatus.IN_PROGRESS;
    }

    public void resolve(Instant now){
        Objects.requireNonNull(now, "now cannot be null");
        if (this.status != TicketStatus.IN_PROGRESS && this.status != TicketStatus.ASSIGNED) {
            throw new BusinessRuleViolationException("Only tickets in ASSIGNED or IN_PROGRESS state can be resolved");
        }
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = now;
    }

    public void cancel(){
        if(this.status == TicketStatus.RESOLVED){
            throw new BusinessRuleViolationException("Cannot cancel an already RESOLVED maintenance ticket");
        }
        this.status = TicketStatus.CANCELLED;
    }

    public void updateDetails(String title, String description, TicketPriority priority){
        if(this.status == TicketStatus.RESOLVED || this.status == TicketStatus.CANCELLED){
            throw new BusinessRuleViolationException("Cannot update details of a ticket that is already " + this.status);
        }
        this.title = Objects.requireNonNull(title, "title cannot be null");
        this.description = description;
        this.priority = Objects.requireNonNull(priority, "priority cannot be null");
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TicketPriority getPriority() { return priority; }
    public TicketStatus getStatus() { return status; }
    public Trail getTrail() { return trail; }
    public Refuge getRefuge() { return refuge; }
    public HazardReport getHazardReport() { return hazardReport; }
    public UUID getAssignedTo() { return assignedTo; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Long getVersion() { return version; }
}
