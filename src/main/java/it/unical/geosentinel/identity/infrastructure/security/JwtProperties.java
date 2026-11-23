/*
 * Proprietà di configurazione degli access token JWT, lette dal prefisso geosentinel.jwt e validate
 * all'avvio dell'applicazione.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configurazione dei JWT.
 *
 * @param secret chiave HMAC condivisa per firmare e verificare i token
 * @param accessTtl durata di un access token
 * @param issuer valore del claim {@code iss}
 */
@Validated
@ConfigurationProperties(prefix = "geosentinel.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT secret cannot be blank")
        @Size(min = 32, message = "JWT secret must be at least 32 characters long for HMAC-SHA256")
        String secret,

        @NotNull(message = "Access token TTL must be specified")
        Duration accessTtl,

        @NotBlank(message = "JWT issuer must be specified")
        String issuer
) {
    /**
     * Fa fallire subito l'avvio con una chiave troppo corta. HS256 richiede almeno 256 bit, mentre
     * {@code @Size} conta i caratteri e non i byte, quindi serve questo controllo in più.
     *
     * @throws IllegalArgumentException se la chiave è più corta di 32 byte in UTF-8
     */
    public JwtProperties {
        if (secret != null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes (256 bit) for HS256");
        }
    }
}
