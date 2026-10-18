package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Una riga per utente con la verifica in due passaggi avviata. Senza enabled_at la configurazione non è confermata
@Entity
@Table(name = "user_mfa")
public class UserMfa {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "secret_enc", nullable = false)
    private byte[] secretEnc;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    // Ultimo passo TOTP accettato: lo stesso codice non vale due volte (anti-replay)
    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserMfa() {}

    public UserMfa(UUID userId, byte[] secretEnc, Instant now) {
        this.userId = Objects.requireNonNull(userId, "User id cannot be null");
        this.secretEnc = Objects.requireNonNull(secretEnc, "Secret cannot be null");
        this.createdAt = Objects.requireNonNull(now, "Created at cannot be null");
    }

    public UUID getUserId() { return userId; }
    public byte[] getSecretEnc() { return secretEnc; }
    public Instant getEnabledAt() { return enabledAt; }
    public Long getLastUsedStep() { return lastUsedStep; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isEnabled() { return enabledAt != null; }

    public void replaceSecret(byte[] newSecretEnc){
        if(isEnabled()){
            throw new IllegalStateException("Cannot replace secret of enabled MFA");
        }
        this.secretEnc = Objects.requireNonNull(newSecretEnc, "New secret cannot be null");
    }

    public void enable(Instant now){
        this.enabledAt = Objects.requireNonNull(now, "Enabled at cannot be null");
    }

    public boolean isStepFresh(long step){
        return lastUsedStep == null || lastUsedStep < step;
    }

    public void useStep(long step){
        if(!isStepFresh(step)){
            throw new IllegalStateException("Step is not fresh: " + step);
        }
        this.lastUsedStep = step;
    }
}
