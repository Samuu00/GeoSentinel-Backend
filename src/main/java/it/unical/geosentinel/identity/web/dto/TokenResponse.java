package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.identity.application.AuthTokens;

import java.time.Instant;
public record TokenResponse(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        String tokenType
) {
    public static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.accessExpiresAt(), tokens.refreshToken(), tokens.tokenType());
    }
}
