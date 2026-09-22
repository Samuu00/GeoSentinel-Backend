package it.unical.geosentinel.identity.application.port;

import java.time.Duration;

public interface TokenBlacklist {
    void revoke(String jti, Duration ttl);
    boolean isRevoked(String jti);
}