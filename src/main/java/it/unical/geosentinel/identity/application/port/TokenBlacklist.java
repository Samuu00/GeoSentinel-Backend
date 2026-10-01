package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface TokenBlacklist {
    void revoke(String jti, Duration ttl);

    // Tutti gli access token dell'utente emessi fino a 'until' compreso (precisione del secondo, come iat), anche
    // quelli degli altri dispositivi che revoke(jti) non conosce (cancellazione dell'account, cambio password)
    void revokeAllIssuedUntil(UUID userId, Instant until);

    boolean isRevoked(String jti, String subject, Instant issuedAt);
}
