package it.unical.geosentinel.refuge.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import it.unical.geosentinel.shared.domain.AuditableEntity;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.locationtech.jts.geom.Point;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
    // Descrizione (V11): tutto facoltativo
    @Column(name = "description", length = 2000)
    private String description;
    @Column(name = "altitude_m")
    private Integer altitudeM;
    @Column(name = "season", length = 200)
    private String season;
    @Column(name = "phone", length = 30)
    private String phone;
    // LAZY con BatchSize: la lista dei rifugi vicini (fino a 50) li legge con una query sola, non una per rifugio
    @ElementCollection
    @CollectionTable(name = "refuge_amenities", joinColumns = @JoinColumn(name = "refuge_id"))
    @Column(name = "amenity", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 50)
    private Set<RefugeAmenity> amenities = new HashSet<>();

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

    /** Descrizione, quota, stagione, telefono e servizi: tutti facoltativi (null o vuoti), testi senza spazi ai bordi. */
    public void describe(String description, Integer altitudeM, String season, String phone, Collection<RefugeAmenity> amenities) {
        if (altitudeM != null && (altitudeM < 0 || altitudeM > 5000)) {
            throw new BusinessRuleViolationException("Altitude must be between 0 and 5000 meters");
        }
        this.description = blankToNull(description);
        this.altitudeM = altitudeM;
        this.season = blankToNull(season);
        this.phone = blankToNull(phone);
        this.amenities.clear();
        if (amenities != null) this.amenities.addAll(amenities);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public boolean isManagedBy(UUID userId) { return managerId.equals(userId); }

    public String getName() { return name; }
    public Point getLocation() { return location; }
    public Integer getCapacity() { return capacity; }
    public UUID getManagerId() { return managerId; }
    public String getDescription() { return description; }
    public Integer getAltitudeM() { return altitudeM; }
    public String getSeason() { return season; }
    public String getPhone() { return phone; }
    // nell'ordine dell'enum, non in quello del Set: l'app li mostra sempre uguali
    public Set<RefugeAmenity> getAmenities() { return amenities.isEmpty() ? EnumSet.noneOf(RefugeAmenity.class) : EnumSet.copyOf(amenities); }
}
