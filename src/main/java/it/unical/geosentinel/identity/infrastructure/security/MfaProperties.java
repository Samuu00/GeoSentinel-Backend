/*
 * Proprietà della verifica in due passaggi, lette dal prefisso geosentinel.mfa: contiene la chiave con cui
 * si cifrano i segreti TOTP nel database.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/**
 * Configurazione della verifica in due passaggi.
 *
 * @param encryptionKey chiave AES-256 codificata in Base64 (variabile d'ambiente {@code MFA_ENCRYPTION_KEY})
 */
@ConfigurationProperties(prefix = "geosentinel.mfa")
public record MfaProperties(String encryptionKey) {
    /**
     * Fa fallire subito l'avvio se la chiave manca o non è valida: AES-256 vuole esattamente 32 byte.
     *
     * @throws IllegalArgumentException se la chiave manca, non è Base64 o non decodifica in 32 byte
     */
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
