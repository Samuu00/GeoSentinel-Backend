/*
 * Controller REST del reset della password con codice via email: richiesta del codice e conferma con la
 * nuova password.
 */
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

/**
 * Endpoint sotto {@code /api/v1/auth/password-reset}, pubblici.
 */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
@Tag(name = "Authentication")
public class PasswordResetController {
    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * Chiede l'invio di un codice. Risponde 204 anche per un'email sconosciuta, così la risposta non rivela
     * se l'account esiste.
     *
     * @param request email dell'account
     * @return 204 senza corpo
     */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "requestPasswordReset", summary = "Send a password reset code by email")
    public ResponseEntity<Void> request(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * Imposta la nuova password con il codice ricevuto. La risposta 422 va dichiarata qui perché il
     * customizer di {@code OpenApiConfig} non la aggiunge da solo.
     *
     * @param request email, codice e nuova password
     * @return 204 senza corpo
     */
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
