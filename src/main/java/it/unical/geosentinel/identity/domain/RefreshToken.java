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

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    // Scadenza assoluta della sessione: copiata identica a ogni rotazione della famiglia
    @Column(name = "family_expires_at", nullable = false, updatable = false)
    private Instant familyExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Token emesso dalla rotazione di questo (ADR-0019): null se non è mai stato ruotato
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    // Richiesto da JPA
    protected RefreshToken() {}

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
    public boolean isRevoked() { return revokedAt != null; }
    // Scaduto se ha superato la propria scadenza o quella assoluta della sessione (estremo incluso)
    public boolean isExpired(Instant now) { return !now.isBefore(expiresAt) || !now.isBefore(familyExpiresAt); }
    public boolean isValid(Instant now) { return !isRevoked() && !isExpired(now); }
    public UUID getReplacedById() { return replacedById; }

    // Periodo di grazia (ADR-0019): solo un token revocato da una rotazione, e da non più di `grace`
    public boolean isInRotationGrace(Instant now, Duration grace) {
        return replacedById != null && revokedAt != null && !now.isAfter(revokedAt.plus(grace));
    }

    public void replacedBy(UUID successorId) {
        this.replacedById = Objects.requireNonNull(successorId, "Successor id cannot be null");
    }

    // Il tempo arriva dal Clock del service: niente Instant.now() nel dominio
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now, "Revoked at cannot be null");
        }
    }
}
