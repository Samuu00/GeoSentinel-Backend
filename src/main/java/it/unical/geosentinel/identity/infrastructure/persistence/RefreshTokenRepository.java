/*
 * Repository Spring Data JPA dei refresh token. Le revoche sono UPDATE condizionali eseguiti direttamente sul
 * database, così sono atomiche anche con richieste concorrenti.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/**
 * Accesso alla tabella {@code refresh_tokens}.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    /**
     * @param token hash SHA-256 del token
     * @return il token, se esiste
     */
    Optional<RefreshToken> findByTokenHash(String token);
    /**
     * Legge solo l'id del proprietario senza caricare il token: il refresh prende il lock sull'utente prima
     * di toccare il token.
     *
     * @param tokenHash hash SHA-256 del token
     * @return l'id dell'utente, se il token esiste
     */
    @Query("SELECT r.user.id FROM RefreshToken r WHERE r.tokenHash = :hash")
    Optional<UUID> findUserIdByTokenHash(@Param("hash") String tokenHash);
    /**
     * Revoca il token solo se è ancora attivo. Tra richieste concorrenti con lo stesso token solo una ottiene 1.
     *
     * @param tokenHash hash SHA-256 del token
     * @param now istante della revoca
     * @return 1 se il token è stato revocato ora, 0 se era già revocato o non esiste
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.tokenHash = :hash AND r.revokedAt IS NULL")
    int revokeIfActive(@Param("hash") String tokenHash, @Param("now") Instant now);
    /**
     * Usato nel periodo di grazia dopo una rotazione: revoca il successore solo se nessuno l'ha ancora usato.
     * L'UPDATE è atomico, quindi tra due richieste simultanee con lo stesso token vecchio ne vince una sola.
     *
     * @param id id del token successore
     * @param now istante della revoca
     * @return 1 se il successore è stato revocato ora, 0 altrimenti
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.id = :id AND r.revokedAt IS NULL")
    int revokeIfActiveById(@Param("id") UUID id, @Param("now") Instant now);
    /**
     * Revoca tutti i token ancora attivi di una famiglia (logout o sospetto furto).
     *
     * @param familyId famiglia da revocare
     * @param now istante della revoca
     * @return il numero di token revocati
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
    /**
     * Revoca tutte le sessioni dell'utente (cambio o reset della password, cancellazione dell'account,
     * collegamento di Google, azzeramento della verifica in due passaggi).
     *
     * @param userId utente
     * @param now istante della revoca
     * @return il numero di token revocati
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Cancella i token delle sessioni scadute. Si guardano le famiglie e non i singoli token: finché la sessione
     * è viva un token vecchio deve restare nel database, altrimenti un suo riuso non verrebbe riconosciuto e la
     * famiglia non verrebbe revocata.
     *
     * @param cutoff istante attuale
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.familyExpiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
