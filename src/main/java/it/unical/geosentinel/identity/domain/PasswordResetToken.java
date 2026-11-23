/*
 * Entità JPA di una richiesta di reset della password. Tiene l'hash del codice inviato via email, la scadenza
 * e il numero di tentativi sbagliati, così il codice non si può indovinare per forza bruta.
 */
package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Codice di reset della password (tabella {@code password_reset_tokens}). Vale una sola volta e fino alla
 * scadenza decisa dal service; dopo troppi tentativi sbagliati si considera consumato.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_password_reset_token_user"))
    private User user;

    /**
     * Hash BCrypt del codice con il prefisso {@code {bcrypt}} del {@code DelegatingPasswordEncoder}.
     * Il codice in chiaro non viene mai salvato.
     */
    @Column(name = "code_hash", nullable = false, updatable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Istante in cui il codice è stato usato o bruciato dai tentativi; {@code null} se è ancora attivo. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Tentativi sbagliati registrati su questo codice. */
    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected PasswordResetToken() {}

    /**
     * @param user utente che ha chiesto il reset
     * @param codeHash hash BCrypt del codice
     * @param expiresAt scadenza del codice
     * @param createdAt istante di creazione
     */
    public PasswordResetToken(User user, String codeHash, Instant expiresAt, Instant createdAt) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.codeHash = Objects.requireNonNull(codeHash, "codeHash must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * @param now istante attuale
     * @return {@code true} se il codice non è stato consumato e non è scaduto
     */
    public boolean isUsable(Instant now){ return consumedAt == null && now.isBefore(expiresAt); }

    /**
     * Registra un tentativo sbagliato. Al raggiungimento di {@code maxAttempts} il codice si consuma e non
     * vale più nemmeno se poi viene inserito giusto. Su un codice già inutilizzabile non fa nulla.
     *
     * @param now istante attuale
     * @param maxAttempts numero massimo di tentativi sbagliati ammessi
     * @throws IllegalArgumentException se {@code maxAttempts} è minore di 1
     */
    public void registerFailure(Instant now, int maxAttempts){
        if(maxAttempts < 1){ throw new IllegalArgumentException("maxAttempts must be greater than 0"); }

        if(!isUsable(now)){ return; }

        failedAttempts++;
        if(failedAttempts >= maxAttempts){
            consume(now);
        }
    }

    /**
     * Segna il codice come consumato. Se lo era già, l'istante originale resta invariato.
     *
     * @param now istante attuale
     */
    public void consume(Instant now){
        if(consumedAt == null) consumedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** @return {@code true} se il codice è stato usato o bruciato dai tentativi */
    public boolean isConsumed(){ return consumedAt != null; }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getCreatedAt() { return createdAt; }
}
