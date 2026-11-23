// Entità JPA di una segnalazione di pericolo lungo un sentiero (tabella hazard_reports). Contiene chi
// l'ha inviata, il tipo, la gravità, la posizione e lo stato. Le transizioni di stato sono metodi
// dell'entità, che rifiutano i passaggi non ammessi con BusinessRuleViolationException.
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

/**
 * Segnalazione di un pericolo. Il ciclo di vita è: {@code SUBMITTED}, poi {@code VERIFIED} oppure
 * {@code REJECTED}, e da {@code VERIFIED} infine {@code RESOLVED}.
 */
@Entity
@Table(name = "hazard_reports")
public class HazardReport extends AuditableEntity {
    /**
     * Id dell'utente che ha inviato la segnalazione. Si salva solo l'id, senza una relazione JPA verso
     * l'utente, così la feature trail non dipende da identity; la chiave esterna verso users esiste nel DDL.
     */
    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    /** Sentiero a cui si riferisce il pericolo; {@code null} se il pericolo non è su un sentiero noto. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private HazardType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    /** Descrizione libera scritta da chi segnala, facoltativa. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Posizione del pericolo, un punto con SRID 4326. */
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HazardStatus status;

    /** Costruttore richiesto da JPA. */
    protected HazardReport() {}

    /**
     * Crea una nuova segnalazione nello stato {@code SUBMITTED}.
     *
     * @param reporterId  utente che segnala
     * @param trail       sentiero di riferimento, può essere {@code null}
     * @param type        tipo di pericolo
     * @param severity    gravità
     * @param description descrizione facoltativa
     * @param location    posizione con SRID 4326
     * @throws NullPointerException     se manca uno dei campi obbligatori
     * @throws IllegalArgumentException se la posizione non ha SRID 4326
     */
    public HazardReport(UUID reporterId, Trail trail, HazardType type, Severity severity, String description, Point location) {
        this.reporterId = Objects.requireNonNull(reporterId, "reporterId cannot be null");
        this.trail = trail;
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.severity = Objects.requireNonNull(severity, "severity cannot be null");
        this.description = description;
        this.location = validateGeometry(location);
        this.status = HazardStatus.SUBMITTED;
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
            throw new IllegalArgumentException("Hazard location must have SRID 4326");
        }
        return location;
    }

    /**
     * Conferma la segnalazione, che diventa visibile a tutti sulla mappa.
     *
     * @throws BusinessRuleViolationException se la segnalazione non è in {@code SUBMITTED}
     */
    public void verify(){
        if(this.status != HazardStatus.SUBMITTED){
            throw new BusinessRuleViolationException(
                    "Cannot verify a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.VERIFIED;
    }

    /**
     * Respinge la segnalazione.
     *
     * @throws BusinessRuleViolationException se la segnalazione non è in {@code SUBMITTED}
     */
    public void reject(){
        if(this.status != HazardStatus.SUBMITTED){
            throw new BusinessRuleViolationException(
                    "Cannot reject a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.REJECTED;
    }

    /**
     * Segna il pericolo come risolto.
     *
     * @throws BusinessRuleViolationException se la segnalazione non è in {@code VERIFIED}
     */
    public void resolve(){
        if(this.status != HazardStatus.VERIFIED){
            throw new BusinessRuleViolationException(
                    "Cannot resolve a hazard report that is in status " + this.status
            );
        }
        this.status = HazardStatus.RESOLVED;
    }

    /**
     * Collega la segnalazione a un sentiero.
     *
     * @param trail sentiero di riferimento, non nullo
     */
    public void assignToTrail(Trail trail) {
        this.trail = Objects.requireNonNull(trail, "trail cannot be null");
    }

    // Getter in sola lettura: lo stato cambia solo attraverso i metodi di transizione.
    public UUID getReporterId() { return reporterId; }
    public Trail getTrail() { return trail; }
    public HazardType getType() { return type; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public Point getLocation() { return location; }
    public HazardStatus getStatus() { return status; }
}
