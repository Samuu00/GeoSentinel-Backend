// Entità JPA di un rifugio (tabella refuges): nome, posizione, posti letto, gestore e una descrizione
// facoltativa con quota, stagione di apertura, telefono e servizi. La capacità è il dato usato per
// controllare le prenotazioni notte per notte.
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

/**
 * Rifugio con i suoi dati descrittivi e il numero di posti letto.
 */
@Entity
@Table(name = "refuges")
public class Refuge extends AuditableEntity{
    /**
     * Nome del rifugio. La lunghezza è la stessa di CreateRefugeRequest e del DDL: la validazione dello
     * schema all'avvio non controlla le lunghezze, quindi vanno tenute allineate a mano.
     */
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    /** Posizione del rifugio, un punto con SRID 4326. */
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;
    /** Numero di posti letto, tra 1 e {@link #MAX_CAPACITY}. */
    @Column(name = "capacity", nullable = false)
    private Integer capacity;
    /** Id del gestore del rifugio, che può modificarne i posti letto. */
    @Column(name = "manager_id", nullable = false)
    private UUID managerId;
    /** Descrizione libera; questo campo e i successivi sono tutti facoltativi. */
    @Column(name = "description", length = 2000)
    private String description;
    /** Quota in metri sul livello del mare. */
    @Column(name = "altitude_m")
    private Integer altitudeM;
    /** Periodo di apertura, in testo libero. */
    @Column(name = "season", length = 200)
    private String season;
    /** Numero di telefono. */
    @Column(name = "phone", length = 30)
    private String phone;
    /**
     * Servizi del rifugio, in una tabella separata. La collezione è LAZY con {@code @BatchSize}: per la
     * lista dei rifugi vicini (fino a 50) Hibernate li legge con una sola query invece che con una per
     * rifugio.
     */
    @ElementCollection
    @CollectionTable(name = "refuge_amenities", joinColumns = @JoinColumn(name = "refuge_id"))
    @Column(name = "amenity", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 50)
    private Set<RefugeAmenity> amenities = new HashSet<>();

    /** Costruttore richiesto da JPA. */
    protected Refuge() {}

    /**
     * Crea un rifugio senza descrizione.
     *
     * @param name      nome
     * @param location  posizione con SRID 4326
     * @param capacity  posti letto
     * @param managerId gestore del rifugio
     * @throws BusinessRuleViolationException se la capacità è fuori dai limiti
     */
    public Refuge(String name, Point location, Integer capacity, UUID managerId) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.location = validateGeometry(location);
        this.capacity = validateCapacity(capacity);
        this.managerId = Objects.requireNonNull(managerId, "managerId cannot be null");
    }

    /** Numero massimo di posti letto, lo stesso dei DTO: vale anche per chi non passa dalla validazione web. */
    public static final int MAX_CAPACITY = 1000;

    /**
     * @param capacity posti letto
     * @return lo stesso valore
     * @throws BusinessRuleViolationException se è nullo o fuori dall'intervallo da 1 a {@link #MAX_CAPACITY}
     */
    private static Integer validateCapacity(Integer capacity) {
        if(capacity == null || capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new BusinessRuleViolationException("Capacity must be between 1 and " + MAX_CAPACITY);
        }
        return capacity;
    }

    /**
     * @param location posizione
     * @return la stessa posizione
     * @throws IllegalArgumentException se lo SRID non è 4326
     */
    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("Refuge location must have SRID 4326");
        }
        return location;
    }

    /**
     * Sostituisce nome, posizione e capacità.
     *
     * @param name     nuovo nome
     * @param location nuova posizione
     * @param capacity nuova capacità
     */
    public void updateDetails(String name, Point location, int capacity) {
        this.name = Objects.requireNonNull(name, "Refuge name cannot be null");
        this.location = validateGeometry(location);
        this.capacity = validateCapacity(capacity);
    }

    /**
     * Cambia il numero di posti letto. Il controllo sulle prenotazioni già confermate è nel service.
     *
     * @param newCapacity nuova capacità
     */
    public void changeCapacity(int newCapacity) { this.capacity = validateCapacity(newCapacity); }

    /**
     * Imposta i dati descrittivi del rifugio, tutti facoltativi. I testi vuoti diventano {@code null} e
     * gli altri vengono salvati senza spazi ai bordi; l'elenco dei servizi sostituisce quello precedente.
     *
     * @param description descrizione
     * @param altitudeM   quota in metri, tra 0 e 5000
     * @param season      periodo di apertura
     * @param phone       telefono
     * @param amenities   servizi, oppure {@code null} per nessuno
     * @throws BusinessRuleViolationException se la quota è fuori dall'intervallo
     */
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

    /**
     * @param value testo da normalizzare
     * @return {@code null} se il testo è nullo o vuoto, altrimenti il testo senza spazi ai bordi
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * @param userId utente da controllare
     * @return {@code true} se l'utente è il gestore di questo rifugio
     */
    public boolean isManagedBy(UUID userId) { return managerId.equals(userId); }

    public String getName() { return name; }
    public Point getLocation() { return location; }
    public Integer getCapacity() { return capacity; }
    public UUID getManagerId() { return managerId; }
    public String getDescription() { return description; }
    public Integer getAltitudeM() { return altitudeM; }
    public String getSeason() { return season; }
    public String getPhone() { return phone; }
    /**
     * Restituisce i servizi in una copia ordinata come l'enum, non nell'ordine casuale del Set, così
     * l'app li mostra sempre nello stesso ordine.
     *
     * @return i servizi del rifugio
     */
    public Set<RefugeAmenity> getAmenities() { return amenities.isEmpty() ? EnumSet.noneOf(RefugeAmenity.class) : EnumSet.copyOf(amenities); }
}
