/*
 * Corpo della richiesta POST /auth/login, il login con email e password.
 */
package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Richiesta di login.
 *
 * @param email email dell'account
 * @param password password in chiaro; oltre 72 byte in UTF-8 viene rifiutata, perché BCrypt non li gestirebbe
 * @param mfaCode facoltativo: serve solo a chi ha la verifica in due passaggi (TOTP di 6 cifre o codice di recupero)
 */
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        String email,

        @NotBlank(message = "Password is required")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String password,

        @Size(max = 32, message = "MFA code is too long")
        String mfaCode
) {}