/*
 * Risposta degli endpoint che aprono o ruotano una sessione (registrazione, login, refresh, cambio password).
 */
package it.unical.geosentinel.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.identity.application.AuthTokens;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Token della sessione. I campi sono marcati come obbligatori nello schema OpenAPI, così nel client Kotlin
 * generato le proprietà non sono nullable.
 *
 * @param accessToken JWT da mandare nell'header {@code Authorization}
 * @param accessExpiresAt scadenza dell'access token
 * @param refreshToken refresh token, valido per un solo uso
 * @param tokenType sempre {@code Bearer}
 */
public record TokenResponse(
        @Schema(requiredMode = REQUIRED) String accessToken,
        @Schema(requiredMode = REQUIRED) Instant accessExpiresAt,
        @Schema(requiredMode = REQUIRED) String refreshToken,
        @Schema(requiredMode = REQUIRED) String tokenType
) {
    /**
     * @param tokens token restituiti dal service
     * @return la risposta HTTP
     */
    public static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.accessExpiresAt(), tokens.refreshToken(), tokens.tokenType());
    }
}
