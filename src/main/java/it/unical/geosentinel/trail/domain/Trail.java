// Entità JPA di un sentiero (tabella trails). Contiene nome, descrizione, difficoltà, stato di
// percorribilità e il tracciato come LineString con SRID 4326. La lunghezza in metri è calcolata dal
// database e la colonna version abilita il lock ottimistico.
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

/**
 * Sentiero del parco. Il cambio di stato passa da {@link #changeStatus}, che impedisce le transizioni non
 * ammesse.
 */
@Entity
@Table(name = "trails")
public class Trail extends AuditableEntity{
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TrailStatus status;

    /** Tracciato del sentiero, almeno due punti con SRID 4326. */
    @Column(name = "path", nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString path;

    /**
     * Lunghezza del tracciato in metri. È una colonna GENERATED di PostgreSQL, calcolata dal database a
     * partire da path. {@code @Generated} fa rileggere il valore dopo INSERT e UPDATE: senza, resterebbe
     * {@code null} dopo la creazione e non aggiornato dopo un cambio di tracciato, e il valore sbagliato
     * finirebbe anche nella cache dei sentieri.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "length_m", insertable = false, updatable = false)
    private Double lengthM;

    /** Versione per il lock ottimistico, incrementata da Hibernate a ogni modifica. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Costruttore richiesto da JPA. */
    protected Trail() {}

    /**
     * Crea un nuovo sentiero nello stato {@code OPEN}.
     *
     * @param name        nome del sentiero
     * @param description descrizione facoltativa
     * @param difficulty  difficoltà
     * @param path        tracciato con SRID 4326
     * @throws NullPointerException     se manca un campo obbligatorio
     * @throws IllegalArgumentException se il tracciato non è valido
     */
    public Trail(String name, String description, Difficulty difficulty, LineString path) {
        this.name = Objects.requireNonNull(name, "Trail name cannot be null");
        this.description = description;
        this.difficulty = Objects.requireNonNull(difficulty, "Trail difficulty cannot be null");
        this.status = TrailStatus.OPEN;
        this.path = validatePath(path);
    }

    /**
     * Controlla che il tracciato esista, abbia SRID 4326 e almeno due punti.
     *
     * @param path tracciato da controllare
     * @return lo stesso tracciato
     * @throws IllegalArgumentException se lo SRID non è 4326 o i punti sono meno di due
     */
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

    /**
     * Sostituisce i dati descrittivi e il tracciato. Lo stato non cambia.
     *
     * @param name        nuovo nome
     * @param description nuova descrizione
     * @param difficulty  nuova difficoltà
     * @param path        nuovo tracciato con SRID 4326
     */
    public void updateDetails(String name, String description, Difficulty difficulty, LineString path) {
        this.name = Objects.requireNonNull(name, "Trail name cannot be null");
        this.description = description;
        this.difficulty = Objects.requireNonNull(difficulty, "Trail difficulty cannot be null");
        this.path = validatePath(path);
    }

    /**
     * Cambia lo stato del sentiero. Se lo stato è già quello richiesto non fa nulla. Un sentiero
     * {@code CLOSED} non può passare direttamente a {@code WARNING}: va prima riaperto.
     *
     * @param status nuovo stato
     * @throws BusinessRuleViolationException per il passaggio da {@code CLOSED} a {@code WARNING}
     */
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
