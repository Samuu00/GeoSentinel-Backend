/*
 * Accesso all'utente autenticato della richiesta corrente, letto dal contesto di Spring Security. I controller
 * e i service lo usano per sapere chi sta facendo la richiesta e con quale ruolo.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Utente corrente ricavato dal JWT della richiesta.
 */
@Component
public class CurrentUser {
    /**
     * @return l'id dell'utente autenticato
     * @throws AuthenticationCredentialsNotFoundException se la richiesta non è autenticata
     */
    public UUID getRequiredUserId(){
        return getUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("User not authenticated"));
    }

    /**
     * Legge l'id dell'utente dal claim {@code sub} del JWT.
     *
     * @return l'id, oppure vuoto se la richiesta non è autenticata con un JWT o il {@code sub} non è un UUID
     */
    public Optional<UUID> getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if(auth == null || !auth.isAuthenticated()) return Optional.empty();

        if(auth.getPrincipal() instanceof Jwt jwt){
            try{
                return Optional.of(UUID.fromString(jwt.getSubject()));
            } catch (IllegalArgumentException e){
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Le authority arrivano dal claim {@code roles} del JWT, quindi un amministratore ha {@code ROLE_ADMIN}.
     * La {@code RoleHierarchy} vale solo nelle espressioni di sicurezza, non qui.
     *
     * @return {@code true} se l'utente corrente è un amministratore
     */
    public boolean isAdmin() {
        return hasAnyAuthority(Set.of("ROLE_ADMIN"));
    }

    /**
     * Comprende anche l'amministratore, come la {@code RoleHierarchy}.
     *
     * @return {@code true} se l'utente corrente è un gestore del parco o un amministratore
     */
    public boolean isParkManager() {
        return hasAnyAuthority(Set.of("ROLE_PARK_MANAGER", "ROLE_ADMIN"));
    }

    /**
     * @param authorities authority accettate
     * @return {@code true} se l'utente corrente ne ha almeno una
     */
    private static boolean hasAnyAuthority(Set<String> authorities) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authorities.contains(a.getAuthority()));
    }
}
