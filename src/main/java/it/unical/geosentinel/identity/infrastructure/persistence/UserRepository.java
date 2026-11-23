/*
 * Repository Spring Data JPA degli utenti.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accesso alla tabella {@code users}.
 */
public interface UserRepository extends JpaRepository<User, UUID>{
    /**
     * L'email deve arrivare già normalizzata con {@link User#normalizeEmail(String)}. Non si usa la variante
     * IgnoreCase perché genera {@code upper(email)} e la query non userebbe l'indice.
     *
     * @param email email normalizzata
     * @return l'utente, se esiste
     */
    Optional<User> findByEmail(String email);
    /**
     * @param email email normalizzata
     * @return {@code true} se esiste un utente con quell'email
     */
    boolean existsByEmail(String email);

    /**
     * Legge l'utente con un lock pessimistico in scrittura ({@code SELECT ... FOR UPDATE}). Serializza cambio
     * password, cancellazione dell'account e refresh dello stesso utente: senza, una rotazione non ancora
     * committata sfuggirebbe a {@code revokeAllOfUser}, perché in READ COMMITTED la sua riga nuova non è visibile.
     *
     * @param id id dell'utente
     * @return l'utente bloccato, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Destinatari delle notifiche push per ruolo: solo account attivi, così un operatore disabilitato non
     * riceve allarmi.
     *
     * @param role ruolo cercato
     * @return gli id degli utenti abilitati con quel ruolo
     */
    @Query("SELECT u.id FROM User u WHERE u.role = :role AND u.enabled = true")
    List<UUID> findEnabledIdsByRole(@Param("role") Role role);
}
