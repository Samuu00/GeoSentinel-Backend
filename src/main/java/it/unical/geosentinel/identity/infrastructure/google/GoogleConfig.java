package it.unical.geosentinel.identity.infrastructure.google;

import it.unical.geosentinel.identity.application.port.GoogleIdentityVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.HashSet;

@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfig {
    // Il metodo restituisce la porta, non un JwtDecoder: un secondo JwtDecoder farebbe fallire SecurityConfig
    @Bean
    GoogleIdentityVerifier googleIdentityVerifier(GoogleProperties properties) {
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        return new GoogleIdTokenVerifier(new HashSet<>(properties.clientIds()), decoder);
    }
}
