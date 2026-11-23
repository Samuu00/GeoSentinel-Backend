/*
 * Repository Spring Data JPA dei codici di reset della password.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Accesso alla tabella {@code password_reset_tokens}. I metodi {@code @Modifying} vanno chiamati dentro una
 * transazione.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    /**
     * Restituisce l'ultimo codice non consumato dell'utente: è l'unico che vale, perché all'emissione di un
     * codice nuovo i precedenti vengono consumati.
     *
     * @param userId utente
     * @return il codice aperto più recente, se c'è
     */
    Optional<PasswordResetToken> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /**
     * Consuma con un solo UPDATE tutti i codici aperti dell'utente (all'emissione di un codice nuovo e dopo un
     * reset riuscito).
     *
     * @param userId utente
     * @param now istante di consumo
     * @return il numero di codici consumati
     */
    @Modifying
    @Query("UPDATE PasswordResetToken p SET p.consumedAt = :now WHERE p.user.id = :userId AND p.consumedAt IS NULL")
    int consumeAllOpenOf(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Cancella i codici scaduti prima di {@code cutoff}; il job notturno passa l'istante attuale meno un giorno.
     *
     * @param cutoff limite di scadenza
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /**
     * Cancella tutti i codici dell'utente, durante la cancellazione dell'account.
     *
     * @param userId utente
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.user.id = :userId")
    int deleteAllOf(@Param("userId") UUID userId);
}
