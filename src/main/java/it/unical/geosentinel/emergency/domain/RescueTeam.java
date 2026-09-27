package it.unical.geosentinel.emergency.domain;

import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.util.Objects;

@Entity
@Table(name = "rescue_teams")
public class RescueTeam extends AuditableEntity{
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;
    @Column(name = "call_sign", nullable = false, length = 32)
    private String callSign;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TeamStatus status;
    @Column(name = "base_location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point baseLocation;
    @Column(name = "contact_number", nullable = false, length = 30)
    private String contactNumber;

    protected RescueTeam() {}

    public RescueTeam(String name, String callSign, Point baseLocation, String contactNumber) {
        this.name = Objects.requireNonNull(name, "Team name cannot be null");
        this.callSign = Objects.requireNonNull(callSign, "Call sign cannot be null");
        this.baseLocation = validateGeometry(baseLocation);
        this.contactNumber = Objects.requireNonNull(contactNumber, "Contact number cannot be null");
        this.status = TeamStatus.AVAILABLE;
    }

    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "Location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("Base location geometry must have SRID 4326");
        }
        return location;
    }

    public void updateDetails(String name, String callSign, Point baseLocation, String contactNumber) {
        this.name = Objects.requireNonNull(name, "Team name cannot be null");
        this.callSign = Objects.requireNonNull(callSign, "Call sign cannot be null");
        this.baseLocation = validateGeometry(baseLocation);
        this.contactNumber = Objects.requireNonNull(contactNumber, "Contact number cannot be null");
    }

    public void deploy(){
        if(this.status != TeamStatus.AVAILABLE) {
            throw new BusinessRuleViolationException("Cannot deploy team '%s'. Current status: %s".formatted(name, status));
        }
        this.status = TeamStatus.ON_MISSION;
    }

    public void release(){
        if(this.status != TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot release team '%s' because it is not ON_MISSION".formatted(name));
        }
        this.status = TeamStatus.AVAILABLE;
    }

    public void setOffDuty(){
        if(this.status == TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot put team '%s' OFF_DUTY while actively ON_MISSION".formatted(name));
        }
        this.status = TeamStatus.OFF_DUTY;
    }

    // da ON_MISSION si torna disponibili solo con release(), cioè chiudendo l'allerta: altrimenti l'allerta resterebbe DISPATCHED con una squadra libera
    public void setAvailable(){
        if(this.status == TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot make team '%s' AVAILABLE while ON_MISSION: resolve or cancel the alert".formatted(name));
        }
        this.status = TeamStatus.AVAILABLE;
    }

    public String getName() { return name; }
    public String getCallSign() { return callSign; }
    public TeamStatus getStatus() { return status; }
    public Point getBaseLocation() { return baseLocation; }
    public String getContactNumber() { return contactNumber; }
}
