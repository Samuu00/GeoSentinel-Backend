package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {
    // Cancellazione dell'account e reset da ADMIN: bulk, senza caricare l'entità
    @Modifying
    @Query("DELETE FROM UserMfa m WHERE m.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}