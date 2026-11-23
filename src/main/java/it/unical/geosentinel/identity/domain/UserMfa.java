/*
 * Entità JPA con lo stato della verifica in due passaggi (TOTP) di un utente: segreto cifrato, data di
 * attivazione, ultimo passo usato e codice di conferma inviato via email durante la configurazione.
 */
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

/**
 * Configurazione TOTP di un utente (tabella {@code user_mfa}, una riga per utente che ha avviato la verifica
 * in due passaggi). Finché {@code enabledAt} è {@code null} la configurazione è iniziata ma non confermata.
 * La gestisce {@code MfaService}.
 */
@Entity
@Table(name = "user_mfa")
public class UserMfa {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Segreto TOTP cifrato con {@link MfaSecretCipher}. */
    @Column(name = "secret_enc", nullable = false)
    private byte[] secretEnc;

    /** Istante di attivazione; {@code null} se la configurazione non è ancora confermata. */
    @Column(name = "enabled_at")
    private Instant enabledAt;

    /** Ultimo passo TOTP accettato: impedisce di riusare lo stesso codice una seconda volta (anti-replay). */
    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Hash SHA-256 del codice inviato all'email durante la configurazione, che prova che chi attiva la
     * verifica in due passaggi controlla quella casella. Torna {@code null} dopo l'attivazione.
     */
    @Column(name = "email_code_hash", length = 64)
    private String emailCodeHash;

    /** Scadenza del codice inviato all'email. */
    @Column(name = "email_code_expires_at")
    private Instant emailCodeExpiresAt;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected UserMfa() {}

    /**
     * @param userId utente che avvia la configurazione
     * @param secretEnc segreto TOTP già cifrato
     * @param now istante di creazione
     */
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
    /** @return {@code true} se la verifica in due passaggi è attiva */
    public boolean isEnabled() { return enabledAt != null; }

    /**
     * Sostituisce il segreto di una configurazione non ancora confermata (l'utente ha ripetuto l'avvio).
     *
     * @param newSecretEnc nuovo segreto cifrato
     * @throws IllegalStateException se la verifica in due passaggi è già attiva
     */
    public void replaceSecret(byte[] newSecretEnc){
        if(isEnabled()){
            throw new IllegalStateException("Cannot replace secret of enabled MFA");
        }
        this.secretEnc = Objects.requireNonNull(newSecretEnc, "New secret cannot be null");
    }

    /**
     * Attiva la verifica in due passaggi e cancella il codice email, che non serve più.
     *
     * @param now istante di attivazione
     */
    public void enable(Instant now){
        this.enabledAt = Objects.requireNonNull(now, "Enabled at cannot be null");
        this.emailCodeHash = null;
        this.emailCodeExpiresAt = null;
    }

    /**
     * Registra un nuovo codice di conferma inviato via email; sostituisce quello precedente.
     *
     * @param codeHash hash SHA-256 del codice
     * @param expiresAt scadenza del codice
     */
    public void issueEmailCode(String codeHash, Instant expiresAt) {
        this.emailCodeHash = Objects.requireNonNull(codeHash, "Code hash cannot be null");
        this.emailCodeExpiresAt = Objects.requireNonNull(expiresAt, "Expires at cannot be null");
    }

    /**
     * Controlla il codice email di conferma. Il confronto sugli hash avviene in tempo costante.
     *
     * @param codeHash hash SHA-256 del codice inserito dall'utente
     * @param now istante attuale
     * @return {@code true} se esiste un codice, non è scaduto e coincide
     */
    public boolean emailCodeMatches(String codeHash, Instant now) {
        return emailCodeHash != null
                && now.isBefore(emailCodeExpiresAt)
                && MessageDigest.isEqual(emailCodeHash.getBytes(StandardCharsets.US_ASCII),
                        codeHash.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param step passo TOTP da controllare
     * @return {@code true} se il passo è successivo all'ultimo già usato
     */
    public boolean isStepFresh(long step){
        return lastUsedStep == null || lastUsedStep < step;
    }

    /**
     * Segna un passo TOTP come usato, così lo stesso codice (o uno di un passo precedente) non vale più.
     *
     * @param step passo da segnare
     * @throws IllegalStateException se il passo non è successivo all'ultimo usato
     */
    public void useStep(long step){
        if(!isStepFresh(step)){
            throw new IllegalStateException("Step is not fresh: " + step);
        }
        this.lastUsedStep = step;
    }
}
