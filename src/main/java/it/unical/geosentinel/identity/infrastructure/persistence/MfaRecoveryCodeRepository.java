/*
 * Repository Spring Data JPA dei codici di recupero della verifica in due passaggi.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Accesso alla tabella {@code mfa_recovery_codes}.
 */
public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {
    /**
     * Segna il codice come usato, ma solo se è ancora libero. L'UPDATE condizionale è atomico: due login
     * concorrenti con lo stesso codice non possono consumarlo entrambi.
     *
     * @param userId utente
     * @param codeHash hash SHA-256 del codice normalizzato
     * @param now istante d'uso
     * @return 1 se il codice è stato consumato ora, 0 se era già usato o non esiste
     */
    @Modifying
    @Query("UPDATE MfaRecoveryCode c SET c.usedAt = :now WHERE c.userId = :userId AND c.codeHash = :hash AND c.usedAt IS NULL")
    int markUsedIfAvailable(@Param("userId") UUID userId, @Param("hash") String codeHash, @Param("now") Instant now);

    /**
     * Cancella tutti i codici dell'utente (rigenerazione, disattivazione, azzeramento o cancellazione
     * dell'account): i vecchi codici smettono di valere.
     *
     * @param userId utente
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM MfaRecoveryCode c WHERE c.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}
