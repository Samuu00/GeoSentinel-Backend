package it.unical.geosentinel.identity.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUser {
    public UUID getRequiredUserId(){
        return getUserId()
                .orElseThrow(() -> new AccessDeniedException("User not authenticated"));
    }

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
}
