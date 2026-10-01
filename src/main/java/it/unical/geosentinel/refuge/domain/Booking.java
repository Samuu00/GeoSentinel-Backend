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

@Entity
@Table(name = "bookings")
public class Booking extends AuditableEntity{
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refuge_id", nullable = false, updatable = false)
    private Refuge refuge;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "check_in", nullable = false, updatable = false)
    private LocalDate checkIn;
    @Column(name = "check_out", nullable = false, updatable = false)
    private LocalDate checkOut;
    @Column(name = "guests", nullable = false, updatable = false)
    private Integer guests;
    // ADR-0012: chiave generata dal client, UNIQUE (user_id, idempotency_key) nel DDL. Un retry restituisce questa riga
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    protected Booking() {}

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

    private static Integer validateGuests(Integer guests) {
        if(guests == null || guests <= 0) {
            throw new BusinessRuleViolationException("Guests must be a positive integer");
        }
        return guests;
    }

    private static void validateDates(LocalDate checkIn, LocalDate checkOut) {
        // stesso vincolo del CHECK (check_out > check_in) nel DDL: check-in == check-out è zero notti
        if(!checkOut.isAfter(checkIn)) {
            throw new BusinessRuleViolationException("Check-in date must be before check-out date");
        }
    }

    public static Booking create(Refuge refuge, UUID userId, UUID idempotencyKey, LocalDate checkIn, LocalDate checkOut, int guests) {
        return new Booking(refuge, userId, idempotencyKey, checkIn, checkOut, guests);
    }

    // stessa richiesta del retry? Se no, la chiave è stata riusata per un'altra prenotazione
    public boolean isSameRequest(UUID refugeId, LocalDate checkIn, LocalDate checkOut, int guests) {
        return refuge.getId().equals(refugeId) && this.checkIn.equals(checkIn)
                && this.checkOut.equals(checkOut) && this.guests == guests;
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        if (this.status == BookingStatus.CANCELLED) return;
        // la data viene da `now` (Clock UTC del service), non da LocalDate.now(): testabile e senza fuso della JVM
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
