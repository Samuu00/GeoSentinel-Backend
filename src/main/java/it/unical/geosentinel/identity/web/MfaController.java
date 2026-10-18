package it.unical.geosentinel.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.identity.application.MfaService;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.identity.web.dto.MfaDtos.MfaCodeRequest;
import it.unical.geosentinel.identity.web.dto.MfaDtos.MfaSetupResponse;
import it.unical.geosentinel.identity.web.dto.MfaDtos.RecoveryCodesResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/mfa")
@Tag(name = "Two-factor authentication", description = "Setup, confirmation, disabling and recovery codes of the TOTP second factor")
public class MfaController {
    private final MfaService mfaService;
    private final CurrentUser currentUser;

    public MfaController(MfaService mfaService, CurrentUser currentUser) {
        this.mfaService = mfaService;
        this.currentUser = currentUser;
    }

    @PostMapping("/setup")
    @Operation(operationId = "startMfaSetup", summary = "Start two-factor setup: returns the secret and the otpauth URI (409 if already enabled)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MfaSetupResponse> startSetup() {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(MfaSetupResponse.from(mfaService.startSetup(userId)));
    }

    @PostMapping("/enable")
    @Operation(operationId = "enableMfa", summary = "Confirm two-factor setup with a TOTP code: returns the recovery codes once",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RecoveryCodesResponse> enable(@Valid @RequestBody MfaCodeRequest request) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(new RecoveryCodesResponse(mfaService.enable(userId, request.code())));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "disableMfa", summary = "Disable two-factor with a TOTP code or a recovery code",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> disable(@Valid @RequestBody MfaCodeRequest request) {
        UUID userId = currentUser.getRequiredUserId();
        mfaService.disable(userId, request.code());
        return ResponseEntity.noContent().build();
    }

    // Solo TOTP: un codice di recupero non rigenera altri codici. I vecchi smettono di valere
    @PostMapping("/recovery-codes")
    @Operation(operationId = "regenerateRecoveryCodes", summary = "Regenerate the recovery codes with a TOTP code",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(@Valid @RequestBody MfaCodeRequest request) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(new RecoveryCodesResponse(mfaService.regenerateRecoveryCodes(userId, request.code())));
    }
}
