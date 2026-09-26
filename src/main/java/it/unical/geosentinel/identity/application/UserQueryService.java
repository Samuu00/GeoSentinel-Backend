package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Letture sugli utenti per le altre feature, che non usano UserRepository
@Service
public class UserQueryService {
    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public boolean isActiveUser(UUID userId) {
        return userRepository.findById(userId).map(User::isEnabled).orElse(false);
    }
}
