package it.unical.geosentinel.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import it.unical.geosentinel.identity.application.AuthService;
import it.unical.geosentinel.identity.application.AuthTokens;
import it.unical.geosentinel.identity.application.RegisterCommand;
import it.unical.geosentinel.identity.web.dto.LoginRequest;
import it.unical.geosentinel.identity.web.dto.RefreshRequest;
import it.unical.geosentinel.identity.web.dto.RegisterRequest;
import it.unical.geosentinel.identity.web.dto.TokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints for user registration, authentication, token refresh and revocation")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(operationId = "register", summary = "Register a new hiker account and obtain access tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registration successful"),
            @ApiResponse(responseCode = "400", description = "Validation error on payload"),
            @ApiResponse(responseCode = "409", description = "Concurrent registration with the same email"),
            @ApiResponse(responseCode = "422", description = "Account with this email already exists"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterCommand command = new RegisterCommand(request.email(), request.password(), request.displayName(), request.birthDate());
        AuthTokens tokens = authService.register(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(TokenResponse.from(tokens));
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Authenticate user credentials and obtain access tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authentication successful"),
            @ApiResponse(responseCode = "400", description = "Validation error on payload"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthTokens tokens = authService.login(request.email(), request.password());
        return ResponseEntity.ok(TokenResponse.from(tokens));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshTokens", summary = "Refresh access tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token refresh successful"),
            @ApiResponse(responseCode = "401", description = "Invalid refresh token"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthTokens tokens = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(TokenResponse.from(tokens));
    }

    @PostMapping("/logout")
    @Operation(
            operationId = "logout",
            summary = "Revoke session, blacklist JWT identifier and revoke refresh token family",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout successful, tokens revoked"),
            @ApiResponse(responseCode = "401", description = "Unauthorized or missing token")
    })
    // body facoltativo: senza refresh token si mette comunque in blacklist il jti dell'access token
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) RefreshRequest request) {
        String refreshToken = (request != null) ? request.refreshToken() : null;
        authService.logout(jwt, refreshToken);
        return ResponseEntity.noContent().build();
    }
}
