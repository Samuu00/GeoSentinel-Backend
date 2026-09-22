package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import it.unical.geosentinel.shared.domain.AuditableEntity;

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

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected User() {
        // Required by JPA
    }

    private User(String email, String passwordHash, String displayName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash cannot be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.enabled = true;
    }

    public static User createHiker(String email, String passwordHash, String displayName) {
        return new User(email, passwordHash, displayName, Role.HIKER);
    }

    public static User create(String email, String passwordHash, String displayName, Role role) {
        return new User(email, passwordHash, displayName, role);
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