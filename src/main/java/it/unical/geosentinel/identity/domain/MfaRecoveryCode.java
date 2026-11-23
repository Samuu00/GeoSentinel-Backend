/*
 * Entità JPA dei codici di recupero della verifica in due passaggi: servono per entrare quando l'utente non ha
 * più accesso all'app di autenticazione. Ogni codice vale una sola volta.
 */
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

/**
 * Codice di recupero di un utente (tabella {@code mfa_recovery_codes}). Nel database c'è solo l'hash SHA-256
 * del codice: il codice in chiaro si mostra all'utente una volta sola, quando viene generato.
 */
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Hash SHA-256 del codice in esadecimale (64 caratteri). */
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    /** Istante in cui il codice è stato usato; {@code null} finché è ancora valido. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected MfaRecoveryCode() {}

    /**
     * @param userId utente a cui appartiene il codice
     * @param codeHash hash SHA-256 del codice in chiaro
     * @param now istante di creazione
     */
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
    /** @return {@code true} se il codice è già stato usato */
    public boolean isUsed() { return usedAt != null; }
}
