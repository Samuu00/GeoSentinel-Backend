/*
 * Punto di accesso in sola lettura agli utenti per gli altri moduli del backend (per esempio le notifiche),
 * che così non dipendono direttamente da UserRepository.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Letture sugli utenti esposte agli altri moduli.
 */
@Service
public class UserQueryService {
    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @param userId utente da controllare
     * @return {@code true} se l'utente esiste ed è abilitato
     */
    @Transactional(readOnly = true)
    public boolean isActiveUser(UUID userId) {
        return userRepository.findById(userId).map(User::isEnabled).orElse(false);
    }

    /**
     * Restituisce gli utenti abilitati con un certo ruolo. Si legge a ogni invio di notifica invece di copiare
     * il ruolo nella tabella dei dispositivi, così un cambio di ruolo o una disattivazione valgono subito.
     *
     * @param role ruolo cercato
     * @return gli id degli utenti abilitati con quel ruolo
     */
    @Transactional(readOnly = true)
    public List<UUID> activeUserIdsWithRole(Role role) {
        return userRepository.findEnabledIdsByRole(role);
    }
}
