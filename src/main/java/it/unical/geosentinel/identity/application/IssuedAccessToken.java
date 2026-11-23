/*
 * Access token appena emesso da AuthService.JwtIssuer, con i dati che servono al service senza dover
 * rileggere il JWT.
 */
package it.unical.geosentinel.identity.application;

import java.time.Instant;

/**
 * Access token emesso.
 *
 * @param tokenValue JWT firmato e serializzato
 * @param jti identificativo univoco del token, usato dalla blacklist al logout
 * @param expiresAt scadenza del token
 */
public record IssuedAccessToken(
        String tokenValue,
        String jti,
        Instant expiresAt
) {}
