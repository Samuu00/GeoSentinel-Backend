/*
 * Corpo delle richieste che portano un refresh token: POST /auth/refresh e POST /auth/logout.
 */
package it.unical.geosentinel.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Richiesta con un refresh token.
 *
 * @param refreshToken refresh token in chiaro ricevuto al login o all'ultimo refresh
 */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}