// Entità JPA di una squadra del Soccorso Alpino, salvata nella tabella rescue_teams.
// Fa parte del dominio delle emergenze: le squadre vengono create dall'amministratore e inviate dagli
// operatori sulle allerte SOS. La classe custodisce anche le regole sui cambi di stato della squadra
// (disponibile, in missione, fuori servizio), così che nessun servizio possa saltarle.
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

/**
 * Squadra di soccorso con nome, nominativo radio, base operativa e numero di contatto.
 * Lo stato ({@link TeamStatus}) cambia solo attraverso i metodi di dominio, che rifiutano le transizioni
 * non ammesse con una {@link BusinessRuleViolationException}. La usano {@code SosService} (invio e
 * rilascio della squadra) e {@code SosAlert}, che la lega all'allerta.
 */
@Entity
@Table(name = "rescue_teams")
public class RescueTeam extends AuditableEntity{
    /** Nome della squadra, unico in tutto il sistema (al massimo 100 caratteri). */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;
    /** Nominativo usato nelle comunicazioni radio. */
    @Column(name = "call_sign", nullable = false, length = 32)
    private String callSign;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TeamStatus status;
    /** Posizione della base in WGS84 (SRID 4326), usata per cercare le squadre vicine a un'allerta. */
    @Column(name = "base_location", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point baseLocation;
    @Column(name = "contact_number", nullable = false, length = 30)
    private String contactNumber;

    /** Costruttore vuoto richiesto da JPA; non va usato dal codice applicativo. */
    protected RescueTeam() {}

    /**
     * Crea una nuova squadra, inizialmente disponibile.
     *
     * @param name nome univoco della squadra
     * @param callSign nominativo radio
     * @param baseLocation posizione della base, con SRID 4326
     * @param contactNumber numero di telefono della squadra
     * @throws NullPointerException se uno dei parametri è null
     * @throws IllegalArgumentException se la posizione non ha SRID 4326
     */
    public RescueTeam(String name, String callSign, Point baseLocation, String contactNumber) {
        this.name = Objects.requireNonNull(name, "Team name cannot be null");
        this.callSign = Objects.requireNonNull(callSign, "Call sign cannot be null");
        this.baseLocation = validateGeometry(baseLocation);
        this.contactNumber = Objects.requireNonNull(contactNumber, "Contact number cannot be null");
        this.status = TeamStatus.AVAILABLE;
    }

    /**
     * Controlla che la posizione esista e sia espressa in WGS84, l'unico sistema di riferimento usato nel
     * database.
     *
     * @param location punto da controllare
     * @return lo stesso punto, se valido
     * @throws IllegalArgumentException se lo SRID non è 4326
     */
    private static Point validateGeometry(Point location) {
        Objects.requireNonNull(location, "Location geometry cannot be null");
        if (location.getSRID() != 4326) {
            throw new IllegalArgumentException("Base location geometry must have SRID 4326");
        }
        return location;
    }

    /**
     * Aggiorna i dati anagrafici della squadra senza toccarne lo stato.
     *
     * @throws IllegalArgumentException se la nuova posizione non ha SRID 4326
     */
    public void updateDetails(String name, String callSign, Point baseLocation, String contactNumber) {
        this.name = Objects.requireNonNull(name, "Team name cannot be null");
        this.callSign = Objects.requireNonNull(callSign, "Call sign cannot be null");
        this.baseLocation = validateGeometry(baseLocation);
        this.contactNumber = Objects.requireNonNull(contactNumber, "Contact number cannot be null");
    }

    /**
     * Mette la squadra in missione. Viene chiamato da {@link SosAlert#dispatch} quando un operatore la invia
     * su un'allerta.
     *
     * @throws BusinessRuleViolationException se la squadra non è disponibile
     */
    public void deploy(){
        if(this.status != TeamStatus.AVAILABLE) {
            throw new BusinessRuleViolationException("Cannot deploy team '%s'. Current status: %s".formatted(name, status));
        }
        this.status = TeamStatus.ON_MISSION;
    }

    /**
     * Libera la squadra alla chiusura dell'allerta a cui era assegnata (risolta o annullata).
     *
     * @throws BusinessRuleViolationException se la squadra non era in missione
     */
    public void release(){
        if(this.status != TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot release team '%s' because it is not ON_MISSION".formatted(name));
        }
        this.status = TeamStatus.AVAILABLE;
    }

    /**
     * Mette la squadra fuori servizio.
     *
     * @throws BusinessRuleViolationException se la squadra è in missione
     */
    public void setOffDuty(){
        if(this.status == TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot put team '%s' OFF_DUTY while actively ON_MISSION".formatted(name));
        }
        this.status = TeamStatus.OFF_DUTY;
    }

    /**
     * Rende di nuovo disponibile una squadra fuori servizio.
     * Una squadra in missione torna disponibile solo con {@link #release()}, cioè chiudendo l'allerta:
     * se si potesse liberarla da qui, l'allerta resterebbe DISPATCHED con una squadra che in realtà è libera.
     *
     * @throws BusinessRuleViolationException se la squadra è in missione
     */
    public void setAvailable(){
        if(this.status == TeamStatus.ON_MISSION) {
            throw new BusinessRuleViolationException("Cannot make team '%s' AVAILABLE while ON_MISSION: resolve or cancel the alert".formatted(name));
        }
        this.status = TeamStatus.AVAILABLE;
    }

    /** @return il nome della squadra */
    public String getName() { return name; }
    /** @return il nominativo radio */
    public String getCallSign() { return callSign; }
    /** @return lo stato operativo attuale */
    public TeamStatus getStatus() { return status; }
    /** @return la posizione della base (SRID 4326) */
    public Point getBaseLocation() { return baseLocation; }
    /** @return il numero di contatto della squadra */
    public String getContactNumber() { return contactNumber; }
}
