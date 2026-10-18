package it.unical.geosentinel.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "user_identities")
public class UserIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_user_identities_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private IdentityProvider provider;

    @Column(name = "subject", nullable = false, updatable = false, length = 255)
    private String subject;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected UserIdentity() {}

    public UserIdentity(User user, IdentityProvider provider, String subject, String email, Instant linkedAt) {
        this.user = Objects.requireNonNull(user, "User cannot be null");
        this.provider = Objects.requireNonNull(provider, "Provider cannot be null");
        this.subject = Objects.requireNonNull(subject, "Subject cannot be null");
        this.email = Objects.requireNonNull(email, "Email cannot be null");
        this.linkedAt = Objects.requireNonNull(linkedAt, "Linked at cannot be null");
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public IdentityProvider getProvider() { return provider; }
    public String getSubject() { return subject; }
    public String getEmail() { return email; }
    public Instant getLinkedAt() { return linkedAt; }
}
