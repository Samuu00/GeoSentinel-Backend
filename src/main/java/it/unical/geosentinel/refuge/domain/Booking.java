// Entità JPA di una prenotazione di posti letto in un rifugio (tabella bookings). Contiene rifugio,
// utente, date di arrivo e partenza, numero di ospiti e la chiave di idempotenza con cui l'app può
// ripetere la richiesta senza creare doppioni.
package it.unical.geosentinel.refuge.domain;

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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Prenotazione di un rifugio. Le notti prenotate vanno da {@code checkIn} incluso a {@code checkOut}
 * escluso. I dati della prenotazione non si modificano: si può solo annullarla.
 */
@Entity
@Table(name = "bookings")
public class Booking extends AuditableEntity{
    /** Rifugio prenotato. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refuge_id", nullable = false, updatable = false)
    private Refuge refuge;
    /** Id dell'utente che ha prenotato; si salva solo l'id per non dipendere dalla feature identity. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    /** Giorno di arrivo, cioè la prima notte. */
    @Column(name = "check_in", nullable = false, updatable = false)
    private LocalDate checkIn;
    /** Giorno di partenza, escluso dalle notti prenotate. */
    @Column(name = "check_out", nullable = false, updatable = false)
    private LocalDate checkOut;
    /** Numero di ospiti, cioè di posti letto occupati per notte. */
    @Column(name = "guests", nullable = false, updatable = false)
    private Integer guests;
    /**
     * Chiave di idempotenza generata dal client (header Idempotency-Key). Nel DDL la coppia
     * (user_id, idempotency_key) è UNIQUE: un ritentativo con la stessa chiave restituisce questa riga
     * invece di crearne un'altra.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    /** Costruttore richiesto da JPA. */
    protected Booking() {}

    /**
     * Costruttore usato da {@link #create}: valida i dati e crea la prenotazione nello stato
     * {@code CONFIRMED}.
     *
     * @throws NullPointerException           se manca un campo obbligatorio
     * @throws BusinessRuleViolationException se ospiti o date non sono validi
     */
    private Booking(Refuge refuge, UUID userId, UUID idempotencyKey, LocalDate checkIn, LocalDate checkOut, int guests) {
        this.refuge = Objects.requireNonNull(refuge, "refuge cannot be null");
        this.userId = Objects.requireNonNull(userId, "userId cannot be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        this.checkIn = Objects.requireNonNull(checkIn, "checkIn cannot be null");
        this.checkOut = Objects.requireNonNull(checkOut, "checkOut cannot be null");
        this.guests = validateGuests(guests);
        validateDates(checkIn, checkOut);
        this.status = BookingStatus.CONFIRMED;
    }

    /**
     * @param guests numero di ospiti
     * @return lo stesso valore, se positivo
     * @throws BusinessRuleViolationException se è nullo, zero o negativo
     */
    private static Integer validateGuests(Integer guests) {
        if(guests == null || guests <= 0) {
            throw new BusinessRuleViolationException("Guests must be a positive integer");
        }
        return guests;
    }

    /**
     * @param checkIn  giorno di arrivo
     * @param checkOut giorno di partenza
     * @throws BusinessRuleViolationException se la partenza non è successiva all'arrivo
     */
    private static void validateDates(LocalDate checkIn, LocalDate checkOut) {
        // Stesso vincolo del CHECK (check_out > check_in) nel DDL: con le due date uguali le notti sarebbero zero.
        if(!checkOut.isAfter(checkIn)) {
            throw new BusinessRuleViolationException("Check-in date must be before check-out date");
        }
    }

    /**
     * Crea una nuova prenotazione confermata. Il controllo dei posti disponibili non avviene qui ma nel
     * service, con il rifugio bloccato da un lock pessimistico.
     *
     * @param refuge         rifugio
     * @param userId         utente che prenota
     * @param idempotencyKey chiave di idempotenza inviata dal client
     * @param checkIn        giorno di arrivo
     * @param checkOut       giorno di partenza
     * @param guests         numero di ospiti
     * @return la prenotazione, non ancora salvata
     */
    public static Booking create(Refuge refuge, UUID userId, UUID idempotencyKey, LocalDate checkIn, LocalDate checkOut, int guests) {
        return new Booking(refuge, userId, idempotencyKey, checkIn, checkOut, guests);
    }

    /**
     * Confronta questa prenotazione con una nuova richiesta arrivata con la stessa chiave di idempotenza.
     * Se i dati coincidono si tratta di un ritentativo; se no, il client ha riusato la chiave per una
     * prenotazione diversa.
     *
     * @return {@code true} se rifugio, date e ospiti coincidono
     */
    public boolean isSameRequest(UUID refugeId, LocalDate checkIn, LocalDate checkOut, int guests) {
        return refuge.getId().equals(refugeId) && this.checkIn.equals(checkIn)
                && this.checkOut.equals(checkOut) && this.guests == guests;
    }

    /**
     * Annulla la prenotazione. L'operazione è idempotente: su una prenotazione già annullata non fa nulla.
     * Si può annullare fino al giorno di arrivo compreso, non dopo.
     *
     * @param now istante corrente, fornito dal service
     * @throws BusinessRuleViolationException se il giorno di arrivo è già passato
     */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        if (this.status == BookingStatus.CANCELLED) return;
        // La data si ricava da now (che viene dal Clock UTC del service) e non da LocalDate.now(): così il
        // metodo è testabile e non dipende dal fuso orario della JVM.
        if (LocalDate.ofInstant(now, ZoneOffset.UTC).isAfter(this.checkIn)) {
            throw new BusinessRuleViolationException("Cannot cancel a booking after the check-in date has started");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public Refuge getRefuge() { return refuge; }
    public UUID getUserId() { return userId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public LocalDate getCheckIn() { return checkIn; }
    public LocalDate getCheckOut() { return checkOut; }
    public Integer getGuests() { return guests; }
    public BookingStatus getStatus() { return status; }
}
