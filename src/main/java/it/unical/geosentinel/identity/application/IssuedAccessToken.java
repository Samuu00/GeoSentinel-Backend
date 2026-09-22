package it.unical.geosentinel.identity.application;

import java.time.Instant;

public record IssuedAccessToken(
        String tokenValue,
        String jti,
        Instant expiresAt
) {}