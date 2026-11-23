/*
 * Emissione degli access token JWT. Implementa l'interfaccia AuthService.JwtIssuer, così il livello
 * application non dipende da Spring Security OAuth2.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.application.AuthService;
import it.unical.geosentinel.identity.application.IssuedAccessToken;
import it.unical.geosentinel.identity.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Crea JWT firmati con HMAC-SHA256 usando la chiave e la durata di {@link JwtProperties}.
 */
@Service
public class JwtTokenService implements AuthService.JwtIssuer {
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties jwtProperties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /**
     * Emette un access token con emittente, date, {@code sub} (id dell'utente), {@code jti} casuale e ruolo.
     *
     * @param user utente a cui intestare il token
     * @return il token firmato con il suo {@code jti} e la scadenza
     */
    @Override
    public IssuedAccessToken issueAccessToken(User user){
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(jwtProperties.accessTtl());
        String jti = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .id(jti)
                // Solo i claim necessari: il contenuto del JWT è leggibile da chiunque lo intercetti,
                // quindi niente dati personali come email o nome
                .claim("roles", List.of(user.getRole().name()))
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        return new IssuedAccessToken(tokenValue, jti, expiresAt);
    }
}
