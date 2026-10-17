package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        // Con o senza trattino, in maiuscolo o no: il dominio normalizza
        @NotBlank(message = "Reset code is required")
        @Size(max = 32, message = "Reset code must be at most 32 characters")
        String code,

        // min in caratteri, max in byte: BCrypt considera solo i primi 72 byte (ADR-0011), come in ChangePasswordRequest
        @NotBlank(message = "New password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String newPassword
) {}