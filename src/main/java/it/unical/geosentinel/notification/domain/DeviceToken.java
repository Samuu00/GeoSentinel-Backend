package it.unical.geosentinel.notification.domain;

import it.unical.geosentinel.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Le scritture passano dall'upsert atomico di DeviceTokenRepository (due registrazioni simultanee dello stesso token
// non finiscono sul vincolo UNIQUE): l'entità serve alle letture e a ddl-auto=validate
@Entity
@Table(name = "device_tokens")
public class DeviceToken extends AuditableEntity {
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, unique = true, updatable = false, length = 1024)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private DevicePlatform platform;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected DeviceToken() {}

    public UUID getUserId() { return userId; }
    public String getToken() { return token; }
    public DevicePlatform getPlatform() { return platform; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
