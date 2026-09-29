package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;

import java.time.Instant;
import java.util.UUID;

// Il profilo non sta nel JWT (solo sub e roles): l'app lo legge da GET /users/me
public record UserProfile(UUID id, String email, String displayName, Role role, Instant createdAt) {
    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.getCreatedAt());
    }
}
