package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "geosentinel.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT secret cannot be blank")
        @Size(min = 32, message = "JWT secret must be at least 32 characters long for HMAC-SHA256")
        String secret,

        @NotNull(message = "Access token TTL must be specified")
        Duration accessTtl,

        @NotNull(message = "Refresh token TTL must be specified")
        Duration refreshTtl,

        @NotBlank(message = "JWT issuer must be specified")
        String issuer
) {
    // Fail-fast all'avvio: HS256 richiede una chiave di almeno 256 bit e @Size conta i caratteri, non i byte
    public JwtProperties {
        if (secret != null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes (256 bit) for HS256");
        }
    }
}
