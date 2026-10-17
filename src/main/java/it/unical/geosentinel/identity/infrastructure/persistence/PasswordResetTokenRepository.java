package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    // L'ultimo codice ancora aperto: quello vale, i precedenti sono stati consumati alla richiesta
    Optional<PasswordResetToken> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    // Alla richiesta di un nuovo codice: tutti gli aperti dell'utente si consumano in un solo UPDATE.
    // Chiamarlo in una @Transactional: @Modifying lo richiede
    @Modifying
    @Query("UPDATE PasswordResetToken p SET p.consumedAt = :now WHERE p.user.id = :userId AND p.consumedAt IS NULL")
    int consumeAllOpenOf(@Param("userId") UUID userId, @Param("now") Instant now);

    // Pulizia notturna: via i codici scaduti prima di cutoff (il job passa now - 1 giorno)
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    // Cancellazione dell'account (UserAccountService.deleteAccount)
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.user.id = :userId")
    int deleteAllOf(@Param("userId") UUID userId);
}