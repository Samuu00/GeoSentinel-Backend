package it.unical.geosentinel.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import it.unical.geosentinel.identity.application.PasswordResetService;
import it.unical.geosentinel.identity.web.dto.PasswordResetConfirmRequest;
import it.unical.geosentinel.identity.web.dto.PasswordResetRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
@Tag(name = "Authentication")
public class PasswordResetController {
    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    // 204 per un email sconosciuta, la risposta non dice se l'account esista o meno
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "requestPasswordReset", summary = "Send a password reset code by email")
    public ResponseEntity<Void> request(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    // Il 422 non lo aggiunge il customizer di OpenApiConfig: va dichiarato qui, come per il register
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "confirmPasswordReset", summary = "Set a new password with the code received by email")
    @ApiResponses({
            @ApiResponse(responseCode = "422", description = "Code wrong, expired, already used or out of attempts")
    })
    public ResponseEntity<Void> confirm(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.email(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
