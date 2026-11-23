// Entità JPA di un token push registrato da un dispositivo, salvata nella tabella device_tokens.
package it.unical.geosentinel.notification.domain;

import it.unical.geosentinel.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Token push di un dispositivo, associato all'utente che l'ha registrato per ultimo.
 * <p>
 * L'entità non ha costruttori pubblici né metodi di modifica: le scritture passano dall'upsert atomico di
 * {@code DeviceTokenRepository}, così due registrazioni simultanee dello stesso token non violano il vincolo
 * UNIQUE. La classe serve alle letture e alla validazione dello schema all'avvio (ddl-auto=validate).
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken extends AuditableEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Token del dispositivo, unico in tutta la tabella e lungo al massimo 1024 caratteri. */
    @Column(name = "token", nullable = false, unique = true, updatable = false, length = 1024)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private DevicePlatform platform;

    /** Ultima volta in cui l'app ha registrato il token: si aggiorna a ogni avvio. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Costruttore vuoto richiesto da JPA. */
    protected DeviceToken() {}

    /** @return l'utente a cui appartiene il token */
    public UUID getUserId() { return userId; }
    /** @return il token del dispositivo */
    public String getToken() { return token; }
    /** @return la piattaforma del dispositivo */
    public DevicePlatform getPlatform() { return platform; }
    /** @return l'istante dell'ultima registrazione */
    public Instant getLastSeenAt() { return lastSeenAt; }
}
