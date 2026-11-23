/*
 * Validatore aggiunto alla catena di verifica dei JWT: oltre a firma e scadenza, controlla che il token non
 * sia stato revocato (logout, cambio password, cancellazione dell'account).
 */
package it.unical.geosentinel.identity.infrastructure.security;

import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rifiuta i JWT senza {@code jti} o presenti nella {@link TokenBlacklist}. Viene registrato nel
 * {@code JwtDecoder} da {@code SecurityConfig}.
 */
@Component
public class JtiBlacklistValidator implements OAuth2TokenValidator<Jwt> {
    private final TokenBlacklist tokenBlacklist;

    public JtiBlacklistValidator(TokenBlacklist tokenBlacklist) {
        this.tokenBlacklist = tokenBlacklist;
    }

    /**
     * @param jwt token già decodificato e con la firma verificata
     * @return successo, oppure un errore {@code invalid_token} se manca il {@code jti} o il token è revocato
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if(jti == null || jti.isBlank()) {
            OAuth2Error error = new OAuth2Error("invalid_token", "JTI is missing", null);
            return OAuth2TokenValidatorResult.failure(error);
        }

        if (tokenBlacklist.isRevoked(jti, jwt.getSubject(), jwt.getIssuedAt())) {
            OAuth2Error error = new OAuth2Error("invalid_token", "JTI is revoked", null);
            return OAuth2TokenValidatorResult.failure(error);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
