package it.unical.geosentinel.identity.application;

// created = true solo per un account nuovo: il controller risponde 201 invece di 200 (ADR-0024)
public record GoogleSignIn(AuthTokens tokens, boolean created) {}