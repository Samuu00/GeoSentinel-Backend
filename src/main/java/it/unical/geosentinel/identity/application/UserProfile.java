/*
 * Vista del profilo dell'utente autenticato. Il JWT contiene solo l'id e il ruolo, quindi l'app legge il resto
 * del profilo da GET /users/me, che restituisce questi dati.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Profilo dell'utente.
 *
 * @param id id dell'utente
 * @param email email normalizzata
 * @param displayName nome mostrato nell'app
 * @param role ruolo dell'account
 * @param createdAt data di creazione dell'account
 * @param hasPassword {@code false} per un account Google che non ha ancora impostato una password
 * @param mfaEnabled se la verifica in due passaggi è attiva
 * @param avatarMediaId id della foto profilo, oppure {@code null}
 */
public record UserProfile(UUID id, String email, String displayName, Role role, Instant createdAt,
                          boolean hasPassword, boolean mfaEnabled, UUID avatarMediaId) {

    /**
     * Costruisce il profilo a partire dall'entità e dai dati che stanno in altre tabelle.
     *
     * @param user utente
     * @param mfaEnabled se la verifica in due passaggi è attiva
     * @param avatarMediaId id della foto profilo, oppure {@code null}
     * @return il profilo
     */
    public static UserProfile from(User user, boolean mfaEnabled, UUID avatarMediaId) {
        return new UserProfile(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getCreatedAt(), user.hasPassword(), mfaEnabled, avatarMediaId);
    }
}
