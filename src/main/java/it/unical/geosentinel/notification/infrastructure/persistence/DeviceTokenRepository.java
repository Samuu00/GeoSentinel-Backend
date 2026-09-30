package it.unical.geosentinel.notification.infrastructure.persistence;

import it.unical.geosentinel.notification.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    // Upsert atomico: un token già registrato passa all'utente che lo registra ora (stesso telefono, altro account).
    // Con find + save due registrazioni simultanee finirebbero sul vincolo UNIQUE (409)
    @Modifying
    @Query(value = """
            INSERT INTO device_tokens (id, user_id, token, platform, last_seen_at, created_at, created_by, updated_at, updated_by)
            VALUES (gen_random_uuid(), :userId, :token, :platform, :now, :now, :userId, :now, :userId)
            ON CONFLICT (token) DO UPDATE
            SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform, last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("token") String token, @Param("platform") String platform,
                @Param("now") Instant now);

    @Query("SELECT d.token FROM DeviceToken d WHERE d.userId IN :userIds")
    List<String> findTokensByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId AND d.token = :token")
    int deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    // chiamato dopo un invio, fuori da ogni transazione: la transazione è la sua
    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.token IN :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
