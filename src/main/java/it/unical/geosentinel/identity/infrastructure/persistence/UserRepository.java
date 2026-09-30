package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>{
    // L'email arriva già normalizzata (User.normalizeEmail): niente IgnoreCase, che genera upper(email) e salta l'indice
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // destinatari delle push per ruolo (M7.4): solo account attivi, un operatore disabilitato non riceve allarmi
    @Query("SELECT u.id FROM User u WHERE u.role = :role AND u.enabled = true")
    List<UUID> findEnabledIdsByRole(@Param("role") Role role);
}
