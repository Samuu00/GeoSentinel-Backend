package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    // ADR-0026: hash del codice mandato all'email allo setup; null dopo l'attivazione
    @Column(name = "email_code_hash", length = 64)
    private String emailCodeHash;

    @Column(name = "email_code_expires_at")
    private Instant emailCodeExpiresAt;

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
        this.emailCodeHash = null;
        this.emailCodeExpiresAt = null;
    }

    // Un codice nuovo sostituisce il precedente
    public void issueEmailCode(String codeHash, Instant expiresAt) {
        this.emailCodeHash = Objects.requireNonNull(codeHash, "Code hash cannot be null");
        this.emailCodeExpiresAt = Objects.requireNonNull(expiresAt, "Expires at cannot be null");
    }

    // Confronto a tempo costante sull'hash
    public boolean emailCodeMatches(String codeHash, Instant now) {
        return emailCodeHash != null
                && now.isBefore(emailCodeExpiresAt)
                && MessageDigest.isEqual(emailCodeHash.getBytes(StandardCharsets.US_ASCII),
                        codeHash.getBytes(StandardCharsets.US_ASCII));
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
