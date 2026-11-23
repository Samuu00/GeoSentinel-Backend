/*
 * Corpo della richiesta POST /auth/password-reset/request, che chiede l'invio di un codice di reset.
 */
package it.unical.geosentinel.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Richiesta di un codice di reset.
 *
 * @param email email dell'account
 */
public record PasswordResetRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email
) {}