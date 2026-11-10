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

public class UserDtos {
    public record UserProfileResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String email,
            @Schema(requiredMode = REQUIRED) String displayName,
            @Schema(requiredMode = REQUIRED) Role role,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) boolean hasPassword,
            @Schema(requiredMode = REQUIRED) boolean mfaEnabled,
            // null se l'utente non ha una foto: campo nullable, quindi senza REQUIRED (regola M7.1)
            UUID avatarMediaId
    ) {
        public static UserProfileResponse from(UserProfile profile) {
            return new UserProfileResponse(profile.id(), profile.email(), profile.displayName(), profile.role(),
                    profile.createdAt(), profile.hasPassword(), profile.mfaEnabled(), profile.avatarMediaId());
        }
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Display name is required")
            @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
            String displayName
    ) {}

    // max in byte su entrambe: oltre 72 byte BCrypt lancia e la risposta sarebbe 500 (ADR-0011), come in RegisterRequest
    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
            String currentPassword,

            @NotBlank(message = "New password is required")
            @Size(min = 12, message = "Password must be at least 12 characters")
            @MaxUtf8Bytes(value = 72, message = "Password must not exceed 72 bytes in UTF-8")
            String newPassword,

            // ADR-0026: obbligatorio con la 2FA attiva (TOTP o codice di recupero), altrimenti 422 invalid-mfa-code
            @Size(max = 32, message = "MFA code must be at most 32 characters long")
            String mfaCode
    ) {}
}
