package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String token);
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.tokenHash = :hash AND r.revokedAt IS NULL")
    int revokeIfActive(@Param("hash") String tokenHash, @Param("now") Instant now);
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
    // Tutte le sessioni dell'utente: cambio password e cancellazione dell'account
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("now") Instant now);

    // Si cancellano solo le famiglie con la sessione scaduta, non i singoli token scaduti: finché la sessione vive,
    // un token vecchio deve restare nel DB, altrimenti il suo riuso non viene riconosciuto e la famiglia non si revoca
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.familyExpiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
