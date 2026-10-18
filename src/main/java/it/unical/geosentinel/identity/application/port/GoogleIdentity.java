package it.unical.geosentinel.identity.application.port;

// Identità di google verificata:
public record GoogleIdentity(String subject, String email, boolean emailVerified, String displayName) {}
