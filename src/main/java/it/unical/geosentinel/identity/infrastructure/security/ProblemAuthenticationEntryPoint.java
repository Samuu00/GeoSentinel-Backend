/*
 * Punto d'ingresso di Spring Security per le richieste non autenticate: risponde 401 con un ProblemDetail
 * (RFC 9457) e con l'header WWW-Authenticate previsto dall'RFC 6750 per i token Bearer.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.unical.geosentinel.identity.infrastructure.redis.RateLimitDecision;
import it.unical.geosentinel.identity.infrastructure.redis.RateLimitPolicy;
import it.unical.geosentinel.identity.infrastructure.redis.RedisRateLimiter;
import it.unical.geosentinel.shared.web.ProblemDetailWriter;
import it.unical.geosentinel.shared.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;

/**
 * Risposta 401 in formato ProblemDetail, con un limite per IP sulle richieste che portano un token rifiutato.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /** Prefisso del secchio che conta, per IP, le richieste arrivate con un token rifiutato. */
    private static final String BAD_TOKEN_KEY_PREFIX = "rl:bad-token:ip:";

    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;
    private final RedisRateLimiter redisRateLimiter;
    /** Stesso limite al minuto delle rotte generiche, fail-open come quello. */
    private final RateLimitPolicy badTokenPolicy;

    public ProblemAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter, Clock clock,
                                           RedisRateLimiter redisRateLimiter, RateLimitProperties properties) {
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
        this.redisRateLimiter = redisRateLimiter;
        this.badTokenPolicy = RateLimitPolicy.perMinute(properties.generalPerMinute());
    }

    /**
     * Scrive la risposta 401, oppure 429 se dallo stesso IP arrivano troppi token rifiutati.
     * <p>
     * Una richiesta con un token non valido viene fermata dal filtro del bearer prima di arrivare a
     * {@link RateLimitFilter}, che quindi non la conterebbe. Per questo i token rifiutati si contano qui, in un
     * secchio per IP separato: un'app che manda un token scaduto e poi fa il refresh ne consuma uno solo.
     *
     * @param request richiesta rifiutata
     * @param response risposta da scrivere
     * @param authException motivo del rifiuto, usato per scegliere l'header e per capire se c'era un token
     * @throws IOException se la scrittura della risposta fallisce
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        if (authException instanceof OAuth2AuthenticationException) {
            RateLimitDecision decision = redisRateLimiter.tryConsume(BAD_TOKEN_KEY_PREFIX + ClientIp.of(request), badTokenPolicy);
            if (!decision.allowed()) {
                RateLimitFilter.writeTooManyRequests(request, response, decision.retryAfterMillis(), problemDetailWriter, clock);
                return;
            }
        }
        // RFC 6750: error="invalid_token" solo se è arrivato un token ed è stato rifiutato; senza token basta "Bearer"
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, authException instanceof OAuth2AuthenticationException
                ? "Bearer error=\"invalid_token\"" : "Bearer");

        // Messaggio generico: il motivo preciso (firma, scadenza, jti revocato) aiuterebbe solo chi attacca
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Full authentication is required to access this resource");

        problemDetail.setType(ProblemTypes.UNAUTHORIZED);
        problemDetail.setTitle("Unauthorized");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", clock.instant());
        problemDetailWriter.write(response, problemDetail);
    }
}
