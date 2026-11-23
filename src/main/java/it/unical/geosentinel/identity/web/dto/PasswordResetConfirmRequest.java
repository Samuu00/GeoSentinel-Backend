/*
 * Corpo della richiesta POST /auth/password-reset/confirm, che imposta la nuova password con il codice
 * ricevuto via email.
 */
package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Conferma del reset della password.
 *
 * @param email email dell'account
 * @param code codice ricevuto, con o senza trattino e in maiuscolo o minuscolo: lo normalizza il dominio
 * @param newPassword nuova password. Il minimo è in caratteri e il massimo in byte, perché BCrypt considera
 *        solo i primi 72 byte (come in {@code ChangePasswordRequest})
 */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @NotBlank(message = "Reset code is required")
        @Size(max = 32, message = "Reset code must be at most 32 characters")
        String code,

        @NotBlank(message = "New password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String newPassword
) {}