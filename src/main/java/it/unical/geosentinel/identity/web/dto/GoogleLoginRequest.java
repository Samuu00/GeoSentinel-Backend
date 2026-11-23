/*
 * Corpo della richiesta POST /auth/google, il login con un ID token di Google Sign-In.
 */
package it.unical.geosentinel.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Richiesta di login con Google.
 *
 * @param idToken ID token ottenuto dall'app con Google Sign-In
 * @param birthDate data di nascita dichiarata dal client; serve solo al primo accesso, quando si crea un
 *        account nuovo, per il controllo dell'età come nella registrazione
 * @param mfaCode facoltativo: serve solo se l'account collegato ha la verifica in due passaggi
 */
public record GoogleLoginRequest(
        @NotBlank(message = "ID token is required")
        @Size(max = 4096, message = "ID token is too long")
        String idToken,

        @Past(message = "Birth date must be in the past")
        LocalDate birthDate,

        @Size(max = 32, message = "MFA code is too long")
        String mfaCode
) {}