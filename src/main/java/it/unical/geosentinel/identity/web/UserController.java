/*
 * Controller REST del profilo dell'utente autenticato: lettura e modifica del profilo, cambio password e
 * cancellazione dell'account.
 */
package it.unical.geosentinel.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unical.geosentinel.identity.application.AuthService;
import it.unical.geosentinel.identity.application.UserAccountService;
import it.unical.geosentinel.identity.infrastructure.security.CurrentUser;
import it.unical.geosentinel.identity.web.dto.TokenResponse;
import it.unical.geosentinel.identity.web.dto.UserDtos.ChangePasswordRequest;
import it.unical.geosentinel.identity.web.dto.UserDtos.UpdateProfileRequest;
import it.unical.geosentinel.identity.web.dto.UserDtos.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoint sotto {@code /api/v1/users/me}.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "User Profile", description = "Current user's profile, password change and account deletion")
public class UserController {
    private final UserAccountService accountService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public UserController(UserAccountService accountService, AuthService authService, CurrentUser currentUser) {
        this.accountService = accountService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    /**
     * @return 200 con il profilo dell'utente autenticato
     */
    @GetMapping
    @Operation(operationId = "getMyProfile", summary = "Get the current user's profile", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserProfileResponse> getProfile() {
        return ResponseEntity.ok(UserProfileResponse.from(accountService.getProfile(currentUser.getRequiredUserId())));
    }

    /**
     * Cambia il nome mostrato nell'app.
     *
     * @param request nuovo nome
     * @return 200 con il profilo aggiornato
     */
    @PatchMapping
    @Operation(operationId = "updateMyProfile", summary = "Update the current user's display name", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(UserProfileResponse.from(accountService.updateDisplayName(userId, request.displayName())));
    }

    /**
     * Cambia la password. Tutte le sessioni vengono revocate e la risposta contiene i token di una sessione
     * nuova, con cui l'app continua.
     *
     * @param request password attuale, nuova password ed eventuale codice di verifica
     * @return 200 con i nuovi token
     */
    @PostMapping("/password")
    @Operation(operationId = "changeMyPassword", summary = "Change password: revokes every session and returns new tokens", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TokenResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = currentUser.getRequiredUserId();
        return ResponseEntity.ok(TokenResponse.from(
                authService.changePassword(userId, request.currentPassword(), request.newPassword(), request.mfaCode())));
    }

    /**
     * Cancella e anonimizza l'account (solo escursionisti; per lo staff 409).
     *
     * @param jwt access token della richiesta, che viene messo nella blacklist
     * @return 204 senza corpo
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteMyAccount", summary = "Delete and anonymize the account (hikers only, 409 for staff)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        accountService.deleteAccount(currentUser.getRequiredUserId(), jwt);
        return ResponseEntity.noContent().build();
    }
}
