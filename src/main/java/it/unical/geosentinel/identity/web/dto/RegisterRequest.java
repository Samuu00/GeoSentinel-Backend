package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        // min in caratteri, max in byte: BCrypt considera solo i primi 72 byte (D11)
        @NotBlank(message = "Password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String password,

        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
        String displayName,

        // Age gate neutro (M7.2.1): la soglia la controlla AuthService con il Clock. @Past usa il fuso della JVM, ma
        // sbaglia solo sulle date di oggi/domani, che la soglia rifiuta comunque: nessuna richiesta valida ne è colpita
        @NotNull(message = "Birth date is required")
        @Past(message = "Birth date must be in the past")
        LocalDate birthDate
) {}