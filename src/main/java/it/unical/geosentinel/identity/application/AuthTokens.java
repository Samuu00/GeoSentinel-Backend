/*
 * Risultato di un'operazione che apre o ruota una sessione (registrazione, login, refresh, cambio password).
 * Il controller lo traduce nel DTO di risposta TokenResponse.
 */
package it.unical.geosentinel.identity.application;

import java.time.Instant;

/**
 * Coppia di token consegnata al client.
 *
 * @param accessToken JWT di breve durata da mandare nell'header {@code Authorization}
 * @param accessExpiresAt scadenza dell'access token
 * @param refreshToken refresh token in chiaro, opaco, da usare una sola volta
 * @param tokenType tipo del token per l'header HTTP, sempre {@code Bearer}
 */
public record AuthTokens(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        String tokenType
) {
    /**
     * Crea i token con tipo {@code Bearer}.
     *
     * @param accessToken JWT firmato
     * @param accessExpiresAt scadenza dell'access token
     * @param refreshToken refresh token in chiaro
     * @return i token pronti per la risposta
     */
    public static AuthTokens bearer(String accessToken, Instant accessExpiresAt, String refreshToken) {
        return new AuthTokens(accessToken, accessExpiresAt, refreshToken, "Bearer");
    }
}
