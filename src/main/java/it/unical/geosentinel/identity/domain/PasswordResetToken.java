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

// Codice di reset della password, vale una volta per 15 min, e dopo troppi errori si consuma
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

    // BCrypt del codice, con il prefisso {bcrypt} del DelegatingPasswordEncoder: il codice in chiaro non si salva mai
    @Column(name = "code_hash", nullable = false, updatable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetToken() {}

    public PasswordResetToken(User user, String codeHash, Instant expiresAt, Instant createdAt) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.codeHash = Objects.requireNonNull(codeHash, "codeHash must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isUsable(Instant now){ return consumedAt == null && now.isBefore(expiresAt); }

    public void registerFailure(Instant now, int maxAttempts){
        if(maxAttempts < 1){ throw new IllegalArgumentException("maxAttempts must be greater than 0"); }

        if(!isUsable(now)){ return; }

        failedAttempts++;
        if(failedAttempts >= maxAttempts){
            consume(now);
        }
    }

    public void consume(Instant now){
        if(consumedAt == null) consumedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public boolean isConsumed(){ return consumedAt != null; }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getCreatedAt() { return createdAt; }
}