// Repository Spring Data JPA dei token push: registrazione con upsert atomico, lettura dei token per utente
// e cancellazioni.
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

/**
 * Accesso alla tabella device_tokens. Lo usa {@code DeviceService}.
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /**
     * Inserisce il token o, se esiste già, lo aggiorna con un solo statement (INSERT ... ON CONFLICT). Un token
     * già registrato passa all'utente che lo registra ora, come succede quando sullo stesso telefono si entra
     * con un altro account. Con una lettura seguita da un salvataggio, due registrazioni simultanee
     * finirebbero entrambe sull'INSERT e una violerebbe il vincolo UNIQUE.
     *
     * @param userId utente che registra il token, usato anche come autore della modifica
     * @param token token del dispositivo
     * @param platform nome della piattaforma (ANDROID o IOS)
     * @param now istante della registrazione
     */
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

    /**
     * @param userIds utenti di cui leggere i token (la collezione non deve essere vuota)
     * @return tutti i token di quegli utenti
     */
    @Query("SELECT d.token FROM DeviceToken d WHERE d.userId IN :userIds")
    List<String> findTokensByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /**
     * Cancella il token solo se appartiene all'utente indicato.
     *
     * @return il numero di righe cancellate (0 o 1)
     */
    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId AND d.token = :token")
    int deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    /**
     * Cancella tutti i token dell'utente.
     *
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    /**
     * Cancella i token segnalati come non validi dal servizio di push. Ha una transazione propria perché viene
     * chiamato dopo un invio, quando non c'è nessuna transazione aperta.
     *
     * @param tokens token da cancellare
     * @return il numero di righe cancellate
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.token IN :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
