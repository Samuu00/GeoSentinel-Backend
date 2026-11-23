/*
 * Configurazione Spring della verifica in due passaggi: abilita MfaProperties e crea il bean che cifra i
 * segreti TOTP.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.domain.MfaSecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * Configurazione dei bean della verifica in due passaggi.
 */
@Configuration
@EnableConfigurationProperties(MfaProperties.class)
class MfaConfig {
    /**
     * @param properties configurazione con la chiave in Base64 (già validata)
     * @return il cifratore dei segreti TOTP
     */
    @Bean
    MfaSecretCipher mfaSecretCipher(MfaProperties properties) {
        return new MfaSecretCipher(Base64.getDecoder().decode(properties.encryptionKey()));
    }
}
