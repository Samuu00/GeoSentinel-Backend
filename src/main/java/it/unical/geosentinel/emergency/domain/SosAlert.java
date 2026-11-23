// Entità JPA di un'allerta SOS, salvata nella tabella sos_alerts.
// È il cuore del dominio delle emergenze: nasce quando un escursionista chiede aiuto dall'app, riceve gli
// aggiornamenti della sua posizione, viene presa in carico da un operatore che invia una squadra e infine
// viene chiusa (risolta o annullata). Le regole sui cambi di stato stanno qui, non nei servizi.
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

/**
 * Allerta SOS lanciata da un escursionista.
 * <p>
 * Contiene la posizione (aggiornata periodicamente dall'app), le eventuali note mediche, la squadra
 * assegnata e l'operatore che l'ha inviata. La classe non ha un campo {@code @Version} per il locking
 * ottimistico: ogni modifica passa da un lock pessimistico sulla riga ({@code findByIdForUpdate} nel
 * repository), che serializza già le scritture concorrenti.
 * <p>
 * Le colonne {@code notified_at} e {@code anonymized_at} esistono nella tabella ma non sono mappate qui:
 * le scrivono soltanto le query native di {@code SosAlertRepository} (notifica agli operatori e
 * anonimizzazione dopo il periodo di conservazione).
 */
@Entity
@Table(name = "sos_alerts")
public class SosAlert extends AuditableEntity {
    /** Id dell'utente che ha lanciato l'SOS; non cambia mai. */
    @Column(name = "hiker_id", nullable = false, updatable = false)
    private UUID hikerId;
    /**
     * Chiave di idempotenza inviata dall'app per riconoscere i retry dello stesso SOS. È facoltativa: un SOS
     * senza chiave viene comunque accettato, perché una richiesta d'aiuto non si rifiuta mai.
     */
    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;
    /** Ultima posizione nota dell'escursionista (SRID 4326). */
    @Column(name = "location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;
    /** Momento dell'ultimo aggiornamento della posizione: dice ai soccorritori quanto è vecchia. */
    @Column(name = "location_updated_at", nullable = false)
    private Instant locationUpdatedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SosStatus status;
    /** Note mediche facoltative scritte dall'escursionista: sono dati sanitari e non vanno mai nei log. */
    @Column(name = "medical_notes", columnDefinition = "TEXT")
    private String medicalNotes;
    /** Squadra inviata sull'allerta, caricata in modo lazy. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_team_id")
    private RescueTeam assignedTeam;
    /**
     * Operatore che ha inviato la squadra. Serve come traccia di audit: il campo updatedBy dell'entità base
     * non basta, perché cambia a ogni aggiornamento di posizione.
     */
    @Column(name = "operator_id")
    private UUID operatorId;
    /**
     * Momento della chiusura (RESOLVED o CANCELLED). Da qui partono i 90 giorni dopo i quali i dati personali
     * vengono anonimizzati da {@code SosDataRetentionJob}.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** Costruttore vuoto richiesto da JPA. */
    protected SosAlert() {}

    /**
     * Costruttore usato da {@link #raise}: l'allerta nasce sempre nello stato ACTIVE.
     *
     * @throws NullPointerException se hikerId o now sono null
     * @throws IllegalArgumentException se la posizione non ha SRID 4326
     */
    private SosAlert(UUID hikerId, UUID idempotencyKey, Point location, String medicalNotes, Instant now) {
        this.hikerId = Objects.requireNonNull(hikerId, "hikerId cannot be null");
        this.idempotencyKey = idempotencyKey;
        this.location = validateGeometry(location);
        this.locationUpdatedAt = Objects.requireNonNull(now, "now cannot be null");
        this.medicalNotes = medicalNotes;
        this.status = SosStatus.ACTIVE;
    }

    /**
     * Controlla che la posizione esista e abbia SRID 4326.
     *
     * @param location punto da controllare
     * @return lo stesso punto, se valido
     * @throws IllegalArgumentException se lo SRID è diverso da 4326
     */
    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "Location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("SOS location geometry must have SRID 4326");
        }
        return location;
    }

    /**
     * Crea una nuova allerta attiva.
     *
     * @param hikerId id dell'escursionista che chiede aiuto
     * @param idempotencyKey chiave di idempotenza della richiesta, può essere null
     * @param location posizione dell'escursionista (SRID 4326)
     * @param medicalNotes note mediche facoltative
     * @param now istante di creazione, usato anche come primo aggiornamento della posizione
     * @return l'allerta, non ancora salvata
     */
    public static SosAlert raise(UUID hikerId, UUID idempotencyKey, Point location, String medicalNotes, Instant now) {
        return new SosAlert(hikerId, idempotencyKey, location, medicalNotes, now);
    }

    /**
     * Dice se l'allerta è ancora aperta.
     *
     * @return true se lo stato è ACTIVE o DISPATCHED
     */
    public boolean isActive() {
        return status == SosStatus.ACTIVE || status == SosStatus.DISPATCHED;
    }

    /**
     * Registra una nuova posizione dell'escursionista e il momento in cui è arrivata.
     *
     * @param newLocation nuova posizione (SRID 4326)
     * @param now istante dell'aggiornamento
     * @throws BusinessRuleViolationException se l'allerta è già chiusa
     */
    public void updateLocation(Point newLocation, Instant now) {
        if(!isActive()) {
            throw new BusinessRuleViolationException("Cannot update location for alert in status " + this.status);
        }
        this.location = validateGeometry(newLocation);
        this.locationUpdatedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    /**
     * Assegna una squadra all'allerta e la mette in missione.
     *
     * @param team squadra da inviare, che deve essere disponibile
     * @param operatorId operatore che prende in carico l'allerta
     * @throws AlertAlreadyTakenException se un altro operatore ha già inviato una squadra (il client riceve
     *         un 409 e può rileggere l'allerta)
     * @throws BusinessRuleViolationException se l'allerta è chiusa o la squadra non è disponibile
     */
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

    /**
     * Chiude l'allerta come risolta e libera l'eventuale squadra assegnata.
     *
     * @param now istante di chiusura
     * @throws BusinessRuleViolationException se l'allerta è già chiusa
     */
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

    /**
     * Annulla l'allerta, libera la squadra e la scollega. L'operazione è idempotente: annullare un'allerta
     * già annullata non fa nulla, così i retry dell'app non producono errori.
     *
     * @param now istante di chiusura
     * @throws BusinessRuleViolationException se l'allerta è già stata risolta
     */
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

    /** @return l'id dell'escursionista che ha lanciato l'allerta */
    public UUID getHikerId() { return hikerId; }
    /** @return la chiave di idempotenza della richiesta, o null se l'app non l'ha inviata */
    public UUID getIdempotencyKey() { return idempotencyKey; }
    /** @return l'ultima posizione nota */
    public Point getLocation() { return location; }
    /** @return l'istante dell'ultimo aggiornamento della posizione */
    public Instant getLocationUpdatedAt() { return locationUpdatedAt; }
    /** @return lo stato attuale dell'allerta */
    public SosStatus getStatus() { return status; }
    /** @return le note mediche, o null */
    public String getMedicalNotes() { return medicalNotes; }
    /** @return la squadra assegnata, o null se nessuna squadra è stata inviata */
    public RescueTeam getAssignedTeam() { return assignedTeam; }
    /** @return l'operatore che ha inviato la squadra, o null */
    public UUID getOperatorId() { return operatorId; }
    /** @return l'istante di chiusura, o null se l'allerta è ancora aperta */
    public Instant getClosedAt() { return closedAt; }
}
