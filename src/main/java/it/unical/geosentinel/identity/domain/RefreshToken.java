/*
 * Entità JPA dei refresh token. Ogni login apre una "famiglia" di token: a ogni refresh il token usato viene
 * revocato e ne nasce uno nuovo nella stessa famiglia (rotazione). Se un token già ruotato viene riusato,
 * si sospetta un furto e si revoca l'intera famiglia.
 */
package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token di una sessione (tabella {@code refresh_tokens}). Nel database si salva solo l'hash SHA-256
 * del token; il valore in chiaro lo conosce solo il client. Lo crea e lo ruota {@code AuthService}.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_refresh_tokens_user"))
    private User user;

    /** Hash SHA-256 del token in esadecimale (64 caratteri). */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    /** Identificativo della sessione: tutti i token nati dalle rotazioni dello stesso login lo condividono. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** Scadenza di questo singolo token. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * Scadenza assoluta della sessione. Si copia identica a ogni rotazione, quindi i refresh non allungano
     * la sessione oltre questo istante.
     */
    @Column(name = "family_expires_at", nullable = false, updatable = false)
    private Instant familyExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Token nato dalla rotazione di questo; {@code null} se questo token non è mai stato ruotato. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected RefreshToken() {}

    /**
     * @param user proprietario della sessione
     * @param tokenHash hash SHA-256 del token in chiaro
     * @param familyId famiglia (sessione) a cui appartiene il token
     * @param expiresAt scadenza del token
     * @param familyExpiresAt scadenza assoluta della sessione
     * @param createdAt istante di emissione
     */
    public RefreshToken(User user, String tokenHash, UUID familyId, Instant expiresAt, Instant familyExpiresAt, Instant createdAt) {
        this.user = Objects.requireNonNull(user, "User cannot be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "Token hash cannot be null");
        this.familyId = Objects.requireNonNull(familyId, "Family id cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expires at cannot be null");
        this.familyExpiresAt = Objects.requireNonNull(familyExpiresAt, "Family expires at cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at cannot be null");
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getFamilyExpiresAt() { return familyExpiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    /** @return {@code true} se il token è stato revocato (da una rotazione, un logout o una revoca di famiglia) */
    public boolean isRevoked() { return revokedAt != null; }
    /**
     * Un token è scaduto quando ha raggiunto la propria scadenza oppure quella assoluta della sessione
     * (l'istante di scadenza conta già come scaduto).
     *
     * @param now istante attuale
     * @return {@code true} se il token è scaduto
     */
    public boolean isExpired(Instant now) { return !now.isBefore(expiresAt) || !now.isBefore(familyExpiresAt); }
    /**
     * @param now istante attuale
     * @return {@code true} se il token non è revocato né scaduto
     */
    public boolean isValid(Instant now) { return !isRevoked() && !isExpired(now); }
    public UUID getReplacedById() { return replacedById; }

    /**
     * Dice se il token è nel periodo di grazia dopo una rotazione. Serve al client che non ha ricevuto la
     * risposta del refresh (rete caduta) e ripresenta il token vecchio: vale solo per un token revocato da una
     * rotazione (ha un successore) e da non più di {@code grace}.
     *
     * @param now istante attuale
     * @param grace durata del periodo di grazia
     * @return {@code true} se il riuso del token può ancora essere accettato
     */
    public boolean isInRotationGrace(Instant now, Duration grace) {
        return replacedById != null && revokedAt != null && !now.isAfter(revokedAt.plus(grace));
    }

    /**
     * Registra il token nato dalla rotazione di questo.
     *
     * @param successorId id del nuovo token
     */
    public void replacedBy(UUID successorId) {
        this.replacedById = Objects.requireNonNull(successorId, "Successor id cannot be null");
    }

    /**
     * Revoca il token, se non lo era già. L'istante arriva dal {@code Clock} del service e non da
     * {@code Instant.now()}, così il dominio resta testabile.
     *
     * @param now istante della revoca
     */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now, "Revoked at cannot be null");
        }
    }
}
