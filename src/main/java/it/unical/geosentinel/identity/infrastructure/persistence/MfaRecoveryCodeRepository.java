package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {
    // Marca il codice solo se è ancora libero: 1 = consumato ora, 0 = già usato o inesistente.
    // UPDATE atomico: due login con lo stesso codice non lo consumano entrambi
    @Modifying
    @Query("UPDATE MfaRecoveryCode c SET c.usedAt = :now WHERE c.userId = :userId AND c.codeHash = :hash AND c.usedAt IS NULL")
    int markUsedIfAvailable(@Param("userId") UUID userId, @Param("hash") String codeHash, @Param("now") Instant now);

    // Rigenerazione e cancellazione: i vecchi codici smettono di valere
    @Modifying
    @Query("DELETE FROM MfaRecoveryCode c WHERE c.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}