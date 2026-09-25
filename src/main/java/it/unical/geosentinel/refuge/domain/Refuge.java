package it.unical.geosentinel.refuge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.locationtech.jts.geom.Point;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refuges")
public class Refuge extends AuditableEntity{
    @Column(name = "name", nullable = false, length = 120) // come CreateRefugeRequest e V4 (validate non controlla le lunghezze)
    private String name;
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;
    @Column(name = "capacity", nullable = false)
    private Integer capacity;
    @Column(name = "manager_id", nullable = false)
    private UUID managerId;

    protected Refuge() {}

    public Refuge(String name, Point location, Integer capacity, UUID managerId) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.location = validateGeometry(location);
        this.capacity = validateCapacity(capacity);
        this.managerId = Objects.requireNonNull(managerId, "managerId cannot be null");
    }

    private static Integer validateCapacity(Integer capacity) {
        if(capacity == null || capacity <= 0) {
            throw new BusinessRuleViolationException("Capacity must be a positive integer");
        }
        return capacity;
    }

    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("Refuge location must have SRID 4326");
        }
        return location;
    }

    public void updateDetails(String name, Point location, int capacity) {
        this.name = Objects.requireNonNull(name, "Refuge name cannot be null");
        this.location = validateGeometry(location);
        this.capacity = validateCapacity(capacity);
    }

    public void changeCapacity(int newCapacity) { this.capacity = validateCapacity(newCapacity); }

    public boolean isManagedBy(UUID userId) { return managerId.equals(userId); }

    public String getName() { return name; }
    public Point getLocation() { return location; }
    public Integer getCapacity() { return capacity; }
    public UUID getManagerId() { return managerId; }
}
