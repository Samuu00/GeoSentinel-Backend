package it.unical.geosentinel.identity.application;

public record RegisterCommand(
        String email,
        String rawPassword,
        String displayName
) {}