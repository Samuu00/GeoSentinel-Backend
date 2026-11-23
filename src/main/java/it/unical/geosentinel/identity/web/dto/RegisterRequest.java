/*
 * Corpo della richiesta POST /auth/register, la registrazione di un nuovo escursionista.
 */
package it.unical.geosentinel.identity.web.dto;

import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Richiesta di registrazione. Le lunghezze massime sono allineate alle colonne della tabella {@code users}.
 *
 * @param email email dell'account
 * @param password password in chiaro. Il minimo è in caratteri e il massimo in byte, perché BCrypt considera
 *        solo i primi 72 byte
 * @param displayName nome mostrato nell'app
 * @param birthDate data di nascita per il controllo dell'età. Il DTO verifica solo che sia nel passato, la
 *        soglia la controlla {@code AuthService} con il {@code Clock}. {@code @Past} usa il fuso della JVM, ma
 *        può sbagliare solo su date di oggi o domani, che la soglia rifiuta comunque
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
        String password,

        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
        String displayName,

        @NotNull(message = "Birth date is required")
        @Past(message = "Birth date must be in the past")
        LocalDate birthDate
) {}