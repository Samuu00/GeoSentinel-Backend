/*
 * DTO degli endpoint del profilo utente (UserController), raccolti in un'unica classe contenitore.
 */
package it.unical.geosentinel.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.identity.application.UserProfile;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.shared.web.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta del profilo.
 */
public class UserDtos {
    /**
     * Profilo dell'utente autenticato.
     *
     * @param id id dell'utente
     * @param email email
     * @param displayName nome mostrato nell'app
     * @param role ruolo, che l'app usa solo per decidere cosa mostrare
     * @param createdAt data di creazione dell'account
     * @param hasPassword {@code false} per un account Google senza password
     * @param mfaEnabled se la verifica in due passaggi è attiva
     * @param avatarMediaId id della foto profilo, {@code null} se l'utente non ne ha una. È l'unico campo
     *        nullable, per questo non è marcato come obbligatorio nello schema
     */
    public record UserProfileResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String email,
            @Schema(requiredMode = REQUIRED) String displayName,
            @Schema(requiredMode = REQUIRED) Role role,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) boolean hasPassword,
            @Schema(requiredMode = REQUIRED) boolean mfaEnabled,
            UUID avatarMediaId
    ) {
        /**
         * @param profile profilo restituito dal service
         * @return la risposta HTTP
         */
        public static UserProfileResponse from(UserProfile profile) {
            return new UserProfileResponse(profile.id(), profile.email(), profile.displayName(), profile.role(),
                    profile.createdAt(), profile.hasPassword(), profile.mfaEnabled(), profile.avatarMediaId());
        }
    }

    /**
     * Modifica del profilo.
     *
     * @param displayName nuovo nome mostrato nell'app
     */
    public record UpdateProfileRequest(
            @NotBlank(message = "Display name is required")
            @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
            String displayName
    ) {}

    /**
     * Cambio password. Il massimo è in byte su entrambe le password: oltre 72 byte BCrypt lancerebbe
     * un'eccezione e la risposta sarebbe un 500.
     *
     * @param currentPassword password attuale
     * @param newPassword nuova password
     * @param mfaCode codice TOTP o di recupero, obbligatorio se la verifica in due passaggi è attiva
     *        (altrimenti 422 {@code invalid-mfa-code})
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
            String currentPassword,

            @NotBlank(message = "New password is required")
            @Size(min = 12, message = "Password must be at least 12 characters")
            @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
            String newPassword,

            @Size(max = 32, message = "MFA code must be at most 32 characters long")
            String mfaCode
    ) {}
}
