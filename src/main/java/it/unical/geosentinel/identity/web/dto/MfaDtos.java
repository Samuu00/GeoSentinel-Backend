package it.unical.geosentinel.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.identity.application.MfaSetupView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public class MfaDtos {
    public record MfaSetupResponse(
            @Schema(requiredMode = REQUIRED)
            String secret,
            @Schema(requiredMode = REQUIRED)
            String otpauthUri
    ) {
        public static MfaSetupResponse from(MfaSetupView view) {
            return new MfaSetupResponse(view.secret(), view.otpauthUri());
        }
    }

    // TOTP a 6 cifre; su disable anche un codice di recupero
    public record MfaCodeRequest(
            @NotBlank(message = "MFA code is required")
            @Size(max = 32, message = "MFA code must be at most 32 characters long")
            String code
    ) {}

    public record RecoveryCodesResponse(
            @Schema(requiredMode = REQUIRED)
            List<String> codes
    ){}

    public record AdminMfaResetRequest(
            @NotBlank(message = "User email is required")
            @Email(message = "Invalid email format")
            @Size(max = 254, message = "Email must be at most 254 characters long")
            String email
    ) {}
}
