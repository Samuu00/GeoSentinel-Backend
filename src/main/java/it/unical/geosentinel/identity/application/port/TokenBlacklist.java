/*
 * Porta della blacklist degli access token. I JWT sono validi fino alla scadenza e il server non li conserva:
 * per revocarli prima (logout, cambio password, cancellazione dell'account) serve un elenco esterno, che
 * l'implementazione tiene su Redis.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Revoca degli access token prima della loro scadenza naturale.
 */
public interface TokenBlacklist {
    /**
     * Revoca un singolo access token.
     *
     * @param jti identificativo del token
     * @param ttl tempo che manca alla scadenza del token: dopo, la revoca non serve più
     * @throws IllegalStateException se la revoca non può essere salvata
     */
    void revoke(String jti, Duration ttl);

    /**
     * Revoca tutti gli access token dell'utente emessi fino a {@code until} compreso, con la precisione del
     * secondo come il claim {@code iat}. Copre anche i token degli altri dispositivi, di cui il server non
     * conosce il {@code jti} (cambio password, cancellazione dell'account).
     *
     * @param userId utente
     * @param until istante di emissione massimo dei token revocati
     * @throws IllegalStateException se la revoca non può essere salvata
     */
    void revokeAllIssuedUntil(UUID userId, Instant until);

    /**
     * @param jti identificativo del token
     * @param subject id dell'utente (claim {@code sub})
     * @param issuedAt istante di emissione (claim {@code iat})
     * @return {@code true} se il token è revocato, oppure se non si può stabilirlo
     */
    boolean isRevoked(String jti, String subject, Instant issuedAt);
}
