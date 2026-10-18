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

@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    // lunghezze allineate ai DTO (RegisterRequest) e da replicare in V2: validate non controlla la lunghezza
    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "age_confirmed_at")
    private Instant ageConfirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected User() {
        // Required by JPA
    }

    private User(String email, String passwordHash, String displayName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.enabled = true;
    }

    public static User createHiker(String email, String passwordHash, String displayName) {
        return new User(email, Objects.requireNonNull(passwordHash, "passwordHash cannot be null"), displayName, Role.HIKER);
    }

    // Account nato dal login Google: nessuna password finché non ne imposta una con il reset via codice
    public static User createHikerWithoutPassword(String email, String displayName) {
        return new User(email, null, displayName, Role.HIKER);
    }

    public static User create(String email, String passwordHash, String displayName, Role role) {
        return new User(email, Objects.requireNonNull(passwordHash, "passwordHash cannot be null"), displayName, role);
    }

    // Usato al collegamento di Google a un account esistente: la password vecchia non vale più
    public void clearPassword() { this.passwordHash = null; }

    public boolean hasPassword() { return passwordHash != null; }

    // Anonimizzazione, non DELETE fisico: le FK (segnalazioni, prenotazioni, SOS) restano valide.
    // L'email libera quella originale per una nuova registrazione; unusablePasswordHash è l'hash di un valore casuale
    // mai restituito (un hash vero, non una stringa qualsiasi: il DelegatingPasswordEncoder lancerebbe su un id sconosciuto)
    public void markDeleted(Instant now, String unusablePasswordHash) {
        this.email = "deleted-" + getId() + "@invalid";
        this.displayName = "Deleted user";
        this.passwordHash = Objects.requireNonNull(unusablePasswordHash, "unusablePasswordHash cannot be null");
        this.enabled = false;
        this.deletedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    // Controllo dell'età passato in registrazione: la data di nascita non si salva, solo quando è stato fatto
    public void confirmAge(Instant now) {
        this.ageConfirmedAt = Objects.requireNonNull(now, "now cannot be null");
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    // Pubblico: il service normalizza allo stesso modo prima di cercare, così la query usa l'indice unico su email
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email cannot be null");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public void updatePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash cannot be null");
    }

    public void updateProfile(String displayName) {
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
    }
    public void disable() {
        this.enabled = false;
    }
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