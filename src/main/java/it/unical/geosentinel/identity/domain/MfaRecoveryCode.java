package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Solo l'hash SHA-256 del codice: il codice in chiaro si mostra una volta sola, alla generazione
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaRecoveryCode() {}

    public MfaRecoveryCode(UUID userId, String codeHash, Instant now) {
        this.userId = Objects.requireNonNull(userId, "User id cannot be null");
        this.codeHash = Objects.requireNonNull(codeHash, "Code hash cannot be null");
        this.createdAt = Objects.requireNonNull(now, "Created at cannot be null");
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isUsed() { return usedAt != null; }
}
