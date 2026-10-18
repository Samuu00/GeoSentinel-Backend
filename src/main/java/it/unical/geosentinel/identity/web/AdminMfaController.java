package it.unical.geosentinel.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.identity.application.MfaService;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.identity.web.dto.MfaDtos.AdminMfaResetRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/mfa-resets")
@Tag(name = "Administration", description = "Two-factor reset after an identity check made outside the app")
public class AdminMfaController {
    private final MfaService mfaService;
    private final CurrentUser currentUser;

    public AdminMfaController(MfaService mfaService, CurrentUser currentUser) {
        this.mfaService = mfaService;
        this.currentUser = currentUser;
    }

    // Chiude le sessioni dell'utente e gli manda un'email. Il titolare deve aver verificato l'identità fuori dall'app
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "resetUserMfa", summary = "Disable two-factor for a user who lost the device and the recovery codes; closes all their sessions", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> resetUserMfa(@Valid @RequestBody AdminMfaResetRequest request) {
        mfaService.adminReset(currentUser.getRequiredUserId(), request.email());
        return ResponseEntity.noContent().build();
    }
}