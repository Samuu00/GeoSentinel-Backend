package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        String email,

        @NotBlank(message = "Password is required")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String password,

        // Facoltativo: serve solo a chi ha la verifica in due passaggi (TOTP a 6 cifre o codice di recupero)
        @Size(max = 32, message = "MFA code is too long")
        String mfaCode
) {}