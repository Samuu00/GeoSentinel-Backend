package it.unical.geosentinel.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// birthDate serve solo al primo accesso (account nuovo), come in registrazione: il client la dichiara
public record GoogleLoginRequest(
        @NotBlank(message = "ID token is required")
        @Size(max = 4096, message = "ID token is too long")
        String idToken,

        @Past(message = "Birth date must be in the past")
        LocalDate birthDate,

        // Facoltativo: obbligatorio solo se l'account collegato ha la verifica in due passaggi
        @Size(max = 32, message = "MFA code is too long")
        String mfaCode
) {}