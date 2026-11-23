// Entità JPA di un punto di interesse (tabella pois): un luogo utile mostrato sulla mappa, come una
// sorgente o un punto panoramico. Un POI può appartenere a un sentiero oppure essere indipendente, ad
// esempio un bivacco raggiungibile da più sentieri.
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

/**
 * Punto di interesse con nome, categoria e posizione. Si crea con i metodi factory {@link #create} e
 * {@link #createForTrail}.
 */
@Entity
@Table(name = "pois")
public class Poi extends AuditableEntity{
    /** Sentiero a cui appartiene il POI; {@code null} per un POI indipendente. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PoiType type;

    /** Posizione del POI, un punto con SRID 4326. */
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    /** Costruttore richiesto da JPA. */
    protected Poi() {}

    /**
     * Costruttore usato dai metodi factory.
     *
     * @param trail    sentiero di appartenenza, può essere {@code null}
     * @param name     nome del POI
     * @param type     categoria
     * @param location posizione con SRID 4326
     */
    private Poi(Trail trail, String name, PoiType type, Point location) {
        this.trail = trail;
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.location = validateGeometry(location);
    }

    /**
     * Controlla che la posizione esista e sia nel sistema di riferimento WGS 84.
     *
     * @param location punto da controllare
     * @return lo stesso punto
     * @throws IllegalArgumentException se lo SRID non è 4326
     */
    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("POI location must have SRID 4326");
        }
        return location;
    }

    /**
     * Crea un POI indipendente da un sentiero, ad esempio un bivacco raggiungibile da più sentieri.
     *
     * @param name     nome del POI
     * @param type     categoria
     * @param location posizione con SRID 4326
     * @return il nuovo POI, non ancora salvato
     */
    public static Poi create(String name, PoiType type, Point location) {
        return new Poi(null, name, type, location);
    }

    /**
     * Crea un POI collegato a un sentiero.
     *
     * @param trail    sentiero di appartenenza, non nullo
     * @param name     nome del POI
     * @param type     categoria
     * @param location posizione con SRID 4326
     * @return il nuovo POI, non ancora salvato
     */
    public static Poi createForTrail(Trail trail, String name, PoiType type, Point location) {
        return new Poi(Objects.requireNonNull(trail, "trail cannot be null"), name, type, location);
    }

    /**
     * Sostituisce nome, categoria e posizione del POI.
     *
     * @param name     nuovo nome
     * @param type     nuova categoria
     * @param location nuova posizione con SRID 4326
     */
    public void update(String name, PoiType type, Point location) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.location = validateGeometry(location);
    }

    /**
     * Collega il POI a un sentiero.
     *
     * @param trail sentiero di appartenenza, non nullo
     */
    public void assignToTrail(Trail trail) {
        this.trail = Objects.requireNonNull(trail, "trail cannot be null");
    }

    /** Rende il POI indipendente, scollegandolo dal sentiero. */
    public void detachFromTrail() {
        this.trail = null;
    }

    public Trail getTrail() { return trail; }
    public String getName() { return name; }
    public PoiType getType() { return type; }
    public Point getLocation() { return location; }
}
