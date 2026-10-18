package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.domain.MfaSecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

@Configuration
@EnableConfigurationProperties(MfaProperties.class)
class MfaConfig {
    @Bean
    MfaSecretCipher mfaSecretCipher(MfaProperties properties) {
        return new MfaSecretCipher(Base64.getDecoder().decode(properties.encryptionKey()));
    }
}
