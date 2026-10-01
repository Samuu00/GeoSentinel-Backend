package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Limiti per minuto del RateLimitFilter. Sotto il CGNAT degli operatori mobili molti utenti escono
// con lo stesso IP: i limiti per IP sono solo un tetto contro gli abusi, il brute force lo ferma il limite
// per account (RedisLoginAttemptLimiter)
@Validated
@ConfigurationProperties(prefix = "geosentinel.rate-limit")
public record RateLimitProperties(
        // login, register e logout (quest'ultimo è autenticato: conta per utente)
        @Positive long loginPerMinute,
        // /auth/refresh: sempre per IP (la rotta ignora il bearer)
        @Positive long refreshPerMinute,
        // tutte le altre rotte: per utente se c'è il bearer, altrimenti per IP
        @Positive long generalPerMinute
) {
}
