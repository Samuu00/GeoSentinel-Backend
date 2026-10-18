package it.unical.geosentinel.identity.application;

public record MfaSetupView(String secret, String otpauthUri) {}