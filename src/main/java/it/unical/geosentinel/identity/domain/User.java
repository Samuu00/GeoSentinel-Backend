/*
 * Entità JPA dell'utente, il centro del modulo identity. Contiene le credenziali (hash della password), il
 * ruolo e lo stato dell'account; le altre entità del sistema (segnalazioni, prenotazioni, SOS) vi fanno
 * riferimento tramite il suo id.
 */
package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import it.unical.geosentinel.shared.domain.AuditableEntity;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Utente di GeoSentinel (tabella {@code users}). L'id e le date di creazione e modifica arrivano da
 * {@link AuditableEntity}. Gli account si creano con i metodi factory statici; la cancellazione è
 * un'anonimizzazione, non una DELETE.
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    /**
     * Email normalizzata (minuscolo, senza spazi). Le lunghezze delle colonne sono allineate ai DTO
     * ({@code RegisterRequest}) e alla migrazione Flyway: la validazione dello schema di Hibernate non
     * controlla le lunghezze, quindi vanno tenute uguali a mano.
     */
    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    /** Hash della password con il prefisso dell'algoritmo; {@code null} per un account Google senza password. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    /** Un account disabilitato non può fare login né refresh. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Istante della cancellazione (anonimizzazione) dell'account; {@code null} per un account attivo. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Istante in cui è stato superato il controllo dell'età minima. La data di nascita non si salva:
     * si conserva solo la prova che il controllo è stato fatto.
     */
    @Column(name = "age_confirmed_at")
    private Instant ageConfirmedAt;

    /** Versione per il locking ottimistico di JPA. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected User() {
    }

    /**
     * Costruttore comune ai metodi factory: normalizza l'email e crea l'account già abilitato.
     *
     * @param email email dell'utente, anche non normalizzata
     * @param passwordHash hash della password, oppure {@code null} per un account senza password
     * @param displayName nome mostrato nell'app
     * @param role ruolo dell'account
     */
    private User(String email, String passwordHash, String displayName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.enabled = true;
    }

    /**
     * Crea un escursionista registrato con email e password.
     *
     * @param email email dell'utente
     * @param passwordHash hash della password
     * @param displayName nome mostrato nell'app
     * @return il nuovo utente, non ancora salvato
     */
    public static User createHiker(String email, String passwordHash, String displayName) {
        return new User(email, Objects.requireNonNull(passwordHash, "passwordHash cannot be null"), displayName, Role.HIKER);
    }

    /**
     * Crea un escursionista nato dal login con Google. Non ha una password finché l'utente non ne imposta
     * una con il reset via codice.
     *
     * @param email email verificata da Google
     * @param displayName nome mostrato nell'app
     * @return il nuovo utente, non ancora salvato
     */
    public static User createHikerWithoutPassword(String email, String displayName) {
        return new User(email, null, displayName, Role.HIKER);
    }

    /**
     * Crea un utente con un ruolo qualsiasi (usato per gli account dello staff).
     *
     * @param email email dell'utente
     * @param passwordHash hash della password
     * @param displayName nome mostrato nell'app
     * @param role ruolo dell'account
     * @return il nuovo utente, non ancora salvato
     */
    public static User create(String email, String passwordHash, String displayName, Role role) {
        return new User(email, Objects.requireNonNull(passwordHash, "passwordHash cannot be null"), displayName, role);
    }

    /**
     * Toglie la password all'account. Si usa quando Google viene collegato a un account esistente: la password
     * scelta da chi aveva registrato quell'email non deve più valere.
     */
    public void clearPassword() { this.passwordHash = null; }

    /** @return {@code true} se l'account ha una password con cui fare login */
    public boolean hasPassword() { return passwordHash != null; }

    /**
     * Anonimizza l'account invece di cancellarlo fisicamente, così le chiavi esterne di segnalazioni,
     * prenotazioni e SOS restano valide. L'email diventa un indirizzo fittizio, il che libera quella originale
     * per una nuova registrazione.
     *
     * @param now istante della cancellazione
     * @param unusablePasswordHash hash di un valore casuale mai restituito a nessuno. Deve essere un hash vero
     *        e non una stringa qualsiasi, perché il {@code DelegatingPasswordEncoder} lancerebbe un'eccezione
     *        su un prefisso di algoritmo sconosciuto
     */
    public void markDeleted(Instant now, String unusablePasswordHash) {
        this.email = "deleted-" + getId() + "@invalid";
        this.displayName = "Deleted user";
        this.passwordHash = Objects.requireNonNull(unusablePasswordHash, "unusablePasswordHash cannot be null");
        this.enabled = false;
        this.deletedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    /**
     * Registra che l'utente ha superato il controllo dell'età in registrazione.
     *
     * @param now istante del controllo
     */
    public void confirmAge(Instant now) {
        this.ageConfirmedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    /** @return {@code true} se l'account è stato cancellato (anonimizzato) */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /**
     * Normalizza un'email togliendo gli spazi ai bordi e portandola in minuscolo. È pubblico perché i service
     * normalizzano allo stesso modo prima di cercare, così la query usa l'indice unico su {@code email}.
     *
     * @param email email da normalizzare
     * @return l'email normalizzata
     */
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email cannot be null");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Sostituisce l'hash della password.
     *
     * @param newPasswordHash hash della nuova password
     */
    public void updatePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash cannot be null");
    }

    /**
     * Aggiorna i dati modificabili del profilo.
     *
     * @param displayName nuovo nome mostrato nell'app
     */
    public void updateProfile(String displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
    }
    /** Disabilita l'account: login e refresh vengono rifiutati. */
    public void disable() {
        this.enabled = false;
    }
    /** Riabilita l'account. */
    public void enable() {
        this.enabled = true;
    }
    public String getEmail() {
        return email;
    }
    public String getPasswordHash() {
        return passwordHash;
    }
    public String getDisplayName() {
        return displayName;
    }
    public Role getRole() {
        return role;
    }
    public boolean isEnabled() {
        return enabled;
    }
    public Long getVersion() {
        return version;
    }
}
