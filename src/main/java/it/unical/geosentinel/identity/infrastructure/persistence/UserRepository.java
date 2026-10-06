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

public interface UserRepository extends JpaRepository<User, UUID>{
    // L'email arriva già normalizzata (User.normalizeEmail): niente IgnoreCase, che genera upper(email) e salta l'indice
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Serializza cambio password, cancellazione dell'account e refresh dello stesso utente: senza, una rotazione non
    // ancora committata sfugge al revokeAllOfUser (READ COMMITTED non vede la sua riga nuova)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    // destinatari delle push per ruolo: solo account attivi, un operatore disabilitato non riceve allarmi
    @Query("SELECT u.id FROM User u WHERE u.role = :role AND u.enabled = true")
    List<UUID> findEnabledIdsByRole(@Param("role") Role role);
}
