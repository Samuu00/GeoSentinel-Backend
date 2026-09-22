package it.unical.geosentinel.identity.application;

import java.time.Instant;

public record AuthTokens(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        String tokenType
) {
    public static AuthTokens bearer(String accessToken, Instant accessExpiresAt, String refreshToken) {
        return new AuthTokens(accessToken, accessExpiresAt, refreshToken, "Bearer");
    }
}