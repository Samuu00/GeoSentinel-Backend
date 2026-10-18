package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.IdentityProvider;
import it.unical.geosentinel.identity.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);
    boolean existsByUser_IdAndProvider(UUID userId, IdentityProvider provider);

    // Lock transazionale per email (namespace a due chiavi, separato da SOS e quota dei media): due primi login Google
    // simultanei (doppio tap, retry) si mettono in fila e il secondo trova l'account del primo invece del vincolo UNIQUE
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, hashtext(:email))", nativeQuery = true)
    Integer lockEmail(@Param("namespace") int namespace, @Param("email") String email);

    // Cancellazione dell'account: l'account è solo disabilitato, quindi il ON DELETE CASCADE della V14 non scatta
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserIdentity i WHERE i.user.id = :userId")
    void deleteAllOf(@Param("userId") UUID userId);
}