// Entità JPA di un ticket di manutenzione, salvata nella tabella maintenance_tickets.
// Un ticket descrive un lavoro da fare su un sentiero, su un rifugio o in risposta a una segnalazione di
// pericolo, e ne segue l'avanzamento fino alla risoluzione.
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

/**
 * Ticket di manutenzione con titolo, priorità, stato, obiettivo e assegnatario.
 * <p>
 * Gli stati seguono il percorso OPEN, ASSIGNED, IN_PROGRESS, RESOLVED, con CANCELLED possibile finché il
 * ticket non è risolto; le transizioni non ammesse lanciano {@link BusinessRuleViolationException}. Il campo
 * {@code version} serve al controllo di concorrenza ottimistico e viene esposto ai client come ETag.
 */
@Entity
@Table(name = "maintenance_tickets")
public class MaintenanceTicket extends AuditableEntity {
    /** Titolo breve, al massimo 150 caratteri. */
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
    /** Sentiero interessato; null per i ticket di un rifugio o di una segnalazione senza sentiero. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trail_id")
    private Trail trail;
    /** Rifugio interessato, se il ticket riguarda un rifugio. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refuge_id")
    private Refuge refuge;
    /** Segnalazione di pericolo da cui è nato il ticket, se c'è. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hazard_report_id")
    private HazardReport hazardReport;
    /** Id dell'utente a cui è assegnato il lavoro. */
    @Column(name = "assigned_to")
    private UUID assignedTo;
    /** Momento della risoluzione, valorizzato solo per i ticket RESOLVED. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Versione per il locking ottimistico: Hibernate la incrementa a ogni aggiornamento. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Costruttore vuoto richiesto da JPA. */
    protected MaintenanceTicket() {}

    /**
     * Costruttore comune ai metodi factory: il ticket nasce sempre OPEN.
     *
     * @throws NullPointerException se titolo o priorità sono null
     * @throws BusinessRuleViolationException se non è indicato nessun obiettivo
     */
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

    /**
     * Controlla che il ticket abbia almeno un obiettivo.
     *
     * @throws BusinessRuleViolationException se sentiero, rifugio e segnalazione sono tutti null
     */
    private void validateTargetAssociation() {
        if (trail == null && refuge == null && hazardReport == null) {
            throw new BusinessRuleViolationException("At least one target association must be provided");
        }
    }

    /**
     * Crea un ticket su un sentiero.
     *
     * @return il nuovo ticket, non ancora salvato
     */
    public static MaintenanceTicket forTrail(String title, String description, TicketPriority priority, Trail trail) {
        return new MaintenanceTicket(title, description, priority, Objects.requireNonNull(trail, "trail cannot be null"), null, null);
    }

    /**
     * Crea un ticket su un rifugio.
     *
     * @return il nuovo ticket, non ancora salvato
     */
    public static MaintenanceTicket forRefuge(String title, String description, TicketPriority priority, Refuge refuge) {
        return new MaintenanceTicket(title, description, priority, null, Objects.requireNonNull(refuge, "refuge cannot be null"), null);
    }

    /**
     * Crea un ticket a partire da una segnalazione di pericolo. Il sentiero viene ereditato dalla segnalazione
     * (può essere null), così il ticket compare anche fra i ticket di quel sentiero e nella relativa cache.
     *
     * @return il nuovo ticket, non ancora salvato
     */
    public static MaintenanceTicket forHazard(String title, String description, TicketPriority priority, HazardReport hazardReport){
        Objects.requireNonNull(hazardReport, "hazardReport cannot be null");
        return new MaintenanceTicket(title, description, priority, hazardReport.getTrail(), null, hazardReport);
    }

    /**
     * Assegna il ticket a un utente. Un ticket OPEN diventa ASSIGNED; uno già ASSIGNED o IN_PROGRESS cambia
     * solo assegnatario.
     *
     * @param operatorId utente a cui assegnare il lavoro
     * @throws BusinessRuleViolationException se il ticket è già risolto o annullato
     */
    public void assign(UUID operatorId){
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        if(this.status == TicketStatus.RESOLVED || this.status == TicketStatus.CANCELLED){
            throw new BusinessRuleViolationException("Cannot assign a ticket that is already " + this.status);
        }
        this.assignedTo = operatorId;
        // Riassegnare un ticket già IN_PROGRESS non deve riportarlo allo stato ASSIGNED.
        if (this.status == TicketStatus.OPEN) {
            this.status = TicketStatus.ASSIGNED;
        }
    }

    /**
     * Segna l'inizio dei lavori. È ammesso anche da OPEN, senza passare dall'assegnazione.
     *
     * @throws BusinessRuleViolationException se il ticket non è OPEN o ASSIGNED
     */
    public void startWork(){
        if(this.status != TicketStatus.ASSIGNED && this.status != TicketStatus.OPEN){
            throw new BusinessRuleViolationException("Cannot start work on a ticket with status " + this.status);
        }
        this.status = TicketStatus.IN_PROGRESS;
    }

    /**
     * Chiude il ticket come risolto e registra l'istante.
     *
     * @param now istante della risoluzione
     * @throws BusinessRuleViolationException se il ticket non è ASSIGNED o IN_PROGRESS
     */
    public void resolve(Instant now){
        Objects.requireNonNull(now, "now cannot be null");
        if (this.status != TicketStatus.IN_PROGRESS && this.status != TicketStatus.ASSIGNED) {
            throw new BusinessRuleViolationException("Only tickets in ASSIGNED or IN_PROGRESS state can be resolved");
        }
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = now;
    }

    /**
     * Annulla il ticket. Annullare un ticket già annullato non dà errore.
     *
     * @throws BusinessRuleViolationException se il ticket è già risolto
     */
    public void cancel(){
        if(this.status == TicketStatus.RESOLVED){
            throw new BusinessRuleViolationException("Cannot cancel an already RESOLVED maintenance ticket");
        }
        this.status = TicketStatus.CANCELLED;
    }

    /**
     * Modifica titolo, descrizione e priorità di un ticket ancora aperto.
     *
     * @throws BusinessRuleViolationException se il ticket è già risolto o annullato
     */
    public void updateDetails(String title, String description, TicketPriority priority){
        if(this.status == TicketStatus.RESOLVED || this.status == TicketStatus.CANCELLED){
            throw new BusinessRuleViolationException("Cannot update details of a ticket that is already " + this.status);
        }
        this.title = Objects.requireNonNull(title, "title cannot be null");
        this.description = description;
        this.priority = Objects.requireNonNull(priority, "priority cannot be null");
    }

    /** @return il titolo */
    public String getTitle() { return title; }
    /** @return la descrizione, o null */
    public String getDescription() { return description; }
    /** @return la priorità */
    public TicketPriority getPriority() { return priority; }
    /** @return lo stato attuale */
    public TicketStatus getStatus() { return status; }
    /** @return il sentiero, o null */
    public Trail getTrail() { return trail; }
    /** @return il rifugio, o null */
    public Refuge getRefuge() { return refuge; }
    /** @return la segnalazione di origine, o null */
    public HazardReport getHazardReport() { return hazardReport; }
    /** @return l'id dell'assegnatario, o null */
    public UUID getAssignedTo() { return assignedTo; }
    /** @return l'istante di risoluzione, o null */
    public Instant getResolvedAt() { return resolvedAt; }
    /** @return la versione corrente, usata come ETag */
    public Long getVersion() { return version; }
}
