package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>{
    // L'email arriva già normalizzata (User.normalizeEmail): niente IgnoreCase, che genera upper(email) e salta l'indice
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
