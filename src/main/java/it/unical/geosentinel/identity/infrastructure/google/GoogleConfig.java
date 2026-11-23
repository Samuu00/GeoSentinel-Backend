/*
 * Configurazione Spring del login con Google: crea il verificatore degli ID token a partire dai client ID
 * dell'app e dall'indirizzo delle chiavi pubbliche di Google.
 */
package it.unical.geosentinel.identity.infrastructure.google;

import it.unical.geosentinel.identity.application.port.GoogleIdentityVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.HashSet;

/**
 * Configurazione dei bean per il login con Google.
 */
@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfig {
    /**
     * Crea il verificatore con un decoder JWT dedicato alle chiavi di Google. Il metodo espone la porta e non
     * il {@code JwtDecoder}: un secondo bean {@code JwtDecoder} nel contesto farebbe fallire {@code SecurityConfig}.
     *
     * @param properties client ID e indirizzo delle chiavi
     * @return il verificatore degli ID token
     */
    @Bean
    GoogleIdentityVerifier googleIdentityVerifier(GoogleProperties properties) {
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        return new GoogleIdTokenVerifier(new HashSet<>(properties.clientIds()), decoder);
    }
}
