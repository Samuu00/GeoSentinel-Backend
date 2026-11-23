// Superclasse comune delle entità JPA dell'applicazione. Fornisce la chiave primaria UUID e i campi di
// audit (chi e quando ha creato o modificato la riga), compilati da AuditingEntityListener con la
// configurazione di JpaAuditingConfig. Definisce anche equals e hashCode in modo sicuro per Hibernate.
package it.unical.geosentinel.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

/**
 * Entità base con identificativo e metadati di audit. Le sottoclassi non hanno setter per questi campi:
 * li valorizzano Hibernate (l'id) e l'auditing di Spring Data (date e autori).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {
    /** Chiave primaria UUID, generata da Hibernate al primo salvataggio. */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Istante di creazione della riga. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Id dell'utente che ha creato la riga; {@code null} se creata senza un utente autenticato. */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Istante dell'ultima modifica. */
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Id dell'utente che ha fatto l'ultima modifica. */
    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Costruttore richiesto da JPA. */
    protected AuditableEntity() {}

    /** @return l'identificativo, {@code null} finché l'entità non è stata salvata */
    public UUID getId() { return id; }

    /** @return l'istante di creazione */
    public Instant getCreatedAt() { return createdAt; }

    /** @return l'id dell'utente che ha creato l'entità */
    public UUID getCreatedBy() { return createdBy; }

    /** @return l'istante dell'ultima modifica */
    public Instant getUpdatedAt() { return updatedAt; }

    /** @return l'id dell'utente che ha fatto l'ultima modifica */
    public UUID getUpdatedBy() { return updatedBy; }

    /**
     * Due entità sono uguali se hanno la stessa classe persistente e lo stesso id non nullo.
     * La classe si confronta risolvendo gli eventuali proxy di Hibernate, che sono sottoclassi generate
     * a runtime: altrimenti un'entità e il suo proxy risulterebbero diverse. Un'entità non ancora
     * salvata (id {@code null}) è uguale solo a se stessa.
     *
     * @param o oggetto da confrontare
     * @return {@code true} se rappresentano la stessa riga
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;

        if (o == null) return false;

        Class<?> oEffectiveClass = o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass() : this.getClass();

        if (thisEffectiveClass != oEffectiveClass) return false;

        AuditableEntity that = (AuditableEntity) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    /**
     * L'hash dipende solo dalla classe persistente e non dall'id, così resta costante anche quando
     * l'id viene assegnato al salvataggio e l'entità si trova già in un {@code HashSet}.
     *
     * @return l'hash della classe persistente
     */
    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
