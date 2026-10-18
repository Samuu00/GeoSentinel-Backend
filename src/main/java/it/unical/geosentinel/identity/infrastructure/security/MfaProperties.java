package it.unical.geosentinel.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

@ConfigurationProperties(prefix = "geosentinel.mfa")
public record MfaProperties(String encryptionKey) {
    // Fail-fast all'avvio: AES-256 vuole esattamente 32 byte, la chiave arriva in base64 dal .env
    public MfaProperties {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalArgumentException("MFA_ENCRYPTION_KEY is missing");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MFA_ENCRYPTION_KEY must be base64", e);
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("MFA_ENCRYPTION_KEY must decode to 32 bytes (AES-256)");
        }
    }
}