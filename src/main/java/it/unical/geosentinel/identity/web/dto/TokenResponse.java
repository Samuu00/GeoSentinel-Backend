package it.unical.geosentinel.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.identity.application.AuthTokens;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// requiredMode = REQUIRED sulle risposte (M7.1): nel client Kotlin generato la proprietà non è nullable
public record TokenResponse(
        @Schema(requiredMode = REQUIRED) String accessToken,
        @Schema(requiredMode = REQUIRED) Instant accessExpiresAt,
        @Schema(requiredMode = REQUIRED) String refreshToken,
        @Schema(requiredMode = REQUIRED) String tokenType
) {
    public static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.accessExpiresAt(), tokens.refreshToken(), tokens.tokenType());
    }
}
