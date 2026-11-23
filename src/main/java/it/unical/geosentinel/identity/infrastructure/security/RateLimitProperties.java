/*
 * Limiti al minuto usati da RateLimitFilter, letti dal prefisso geosentinel.rate-limit.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configurazione del rate limit per richiesta. Sotto il CGNAT degli operatori mobili molti utenti escono
 * con lo stesso IP: i limiti per IP sono solo un tetto contro gli abusi, mentre il brute force lo ferma il
 * limite per account ({@code RedisLoginAttemptLimiter}).
 *
 * @param loginPerMinute richieste al minuto per login, registrazione e logout (il logout è autenticato e
 *        conta per utente)
 * @param refreshPerMinute richieste al minuto per il refresh, sempre per IP perché la rotta ignora il bearer
 * @param generalPerMinute richieste al minuto per tutte le altre rotte, per utente se c'è il bearer,
 *        altrimenti per IP
 */
@Validated
@ConfigurationProperties(prefix = "geosentinel.rate-limit")
public record RateLimitProperties(
        @Positive long loginPerMinute,
        @Positive long refreshPerMinute,
        @Positive long generalPerMinute
) {
}
