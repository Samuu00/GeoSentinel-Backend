/*
 * Repository Spring Data JPA delle identità esterne (Google) collegate agli account.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.IdentityProvider;
import it.unical.geosentinel.identity.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Accesso alla tabella {@code user_identities}.
 */
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
    /**
     * @param provider fornitore
     * @param subject identificativo dell'utente presso il fornitore
     * @return l'identità collegata, se esiste
     */
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);
    /**
     * @param userId utente
     * @param provider fornitore
     * @return {@code true} se l'utente ha già un'identità di quel fornitore
     */
    boolean existsByUser_IdAndProvider(UUID userId, IdentityProvider provider);

    /**
     * Prende un advisory lock di PostgreSQL legato alla transazione, con due chiavi: un namespace (separato da
     * quelli di SOS e quota dei media) e l'hash dell'email. Due primi login Google simultanei con la stessa
     * email (doppio tocco, retry) si mettono in fila e il secondo trova l'account creato dal primo, invece di
     * fallire sul vincolo UNIQUE. Il lock si rilascia da solo alla fine della transazione.
     *
     * @param namespace prima chiave del lock
     * @param email email normalizzata
     * @return sempre 1
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, hashtext(:email))", nativeQuery = true)
    Integer lockEmail(@Param("namespace") int namespace, @Param("email") String email);

    /**
     * Cancella le identità dell'utente durante la cancellazione dell'account. Serve farlo a mano perché
     * l'account viene solo anonimizzato e disabilitato, quindi l'{@code ON DELETE CASCADE} della tabella non scatta.
     *
     * @param userId utente
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserIdentity i WHERE i.user.id = :userId")
    void deleteAllOf(@Param("userId") UUID userId);
}
