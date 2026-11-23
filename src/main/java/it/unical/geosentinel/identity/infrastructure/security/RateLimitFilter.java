/*
 * Filtro HTTP che applica il rate limit a tutte le richieste dell'API, con limiti diversi per gli endpoint di
 * autenticazione, per il refresh e per il resto. Quando il limite è superato risponde 429 con un ProblemDetail
 * e l'header Retry-After.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.unical.geosentinel.identity.infrastructure.redis.RedisRateLimiter;
import it.unical.geosentinel.identity.infrastructure.redis.RateLimitPolicy;
import it.unical.geosentinel.identity.infrastructure.redis.RateLimitDecision;
import it.unical.geosentinel.shared.web.ProblemDetailWriter;
import it.unical.geosentinel.shared.web.ProblemTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.net.URI;

/**
 * Rate limit per richiesta basato su {@link RedisRateLimiter}. Non è un bean: lo crea e lo inserisce nella
 * catena dei filtri {@code SecurityConfig}, dopo l'autenticazione del bearer token.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final String AUTH_PATH = "/api/v1/auth";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private final RedisRateLimiter redisRateLimiter;
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;
    /** Politica degli endpoint di autenticazione, fail-closed: con Redis giù il login resta comunque limitato. */
    private final RateLimitPolicy loginPolicy;
    /** Politica del refresh, anch'essa fail-closed. */
    private final RateLimitPolicy refreshPolicy;
    /** Politica di tutte le altre rotte, fail-open. */
    private final RateLimitPolicy generalPolicy;

    public RateLimitFilter(RedisRateLimiter redisRateLimiter, ProblemDetailWriter problemDetailWriter, Clock clock,
                           RateLimitProperties properties) {
        this.redisRateLimiter = redisRateLimiter;
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
        this.loginPolicy = RateLimitPolicy.perMinute(properties.loginPerMinute()).withFailClosed();
        this.refreshPolicy = RateLimitPolicy.perMinute(properties.refreshPerMinute()).withFailClosed();
        this.generalPolicy = RateLimitPolicy.perMinute(properties.generalPerMinute());
    }

    /**
     * Esclude dal limite l'invio di un SOS, che non deve mai ricevere un 429. L'abuso resta contenuto perché
     * l'invio restituisce l'allerta già attiva invece di crearne un'altra.
     *
     * @param request richiesta corrente
     * @return {@code true} per {@code POST /api/v1/sos}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/v1/sos".equals(request.getRequestURI());
    }

    /**
     * Sceglie la politica in base al percorso, consuma un gettone e lascia passare la richiesta oppure risponde 429.
     *
     * @param request richiesta corrente
     * @param response risposta
     * @param filterChain resto della catena dei filtri
     * @throws ServletException se un filtro successivo fallisce
     * @throws IOException se la scrittura della risposta fallisce
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        RateLimitPolicy policy;
        String prefix;
        // Un bucket per politica: con una chiave condivisa le GET pubbliche consumerebbero il bucket del login,
        // e i refresh (frequenti quando molti utenti condividono l'IP) consumerebbero i tentativi di login
        if (path.equals(REFRESH_PATH)) {
            policy = refreshPolicy;
            prefix = "rl:refresh:";
        } else if (path.startsWith(AUTH_PATH)) {
            policy = loginPolicy;
            prefix = "rl:auth:";
        } else {
            policy = generalPolicy;
            prefix = "rl:";
        }

        // Chiave per utente se la richiesta è autenticata, altrimenti per IP (vedi ClientIp: IPv6 per rete /64).
        // Il filtro gira dopo BearerTokenAuthenticationFilter, quindi con il bearer anche le GET pubbliche contano
        // per utente. Su login, registrazione, refresh, Google e reset della password il bearer viene ignorato
        // (SecurityConfig.TOKENLESS_AUTH_PATHS), quindi lì la chiave è sempre l'IP e non si aggira con altri token
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String rateLimitKey = (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt)
                ? prefix + "user:" + jwt.getSubject()
                : prefix + "ip:" + ClientIp.of(request);

        RateLimitDecision decision = redisRateLimiter.tryConsume(rateLimitKey, policy);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));

        if (!decision.allowed()) {
            writeTooManyRequests(request, response, decision.retryAfterMillis(), problemDetailWriter, clock);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Scrive la risposta 429 con l'header Retry-After e un ProblemDetail. La usa anche
     * {@link ProblemAuthenticationEntryPoint} per i token non validi.
     *
     * @param request richiesta rifiutata
     * @param response risposta da scrivere
     * @param retryAfterMillis attesa calcolata dal token bucket, in millisecondi
     * @param problemDetailWriter scrittore del ProblemDetail
     * @param clock orologio per il timestamp
     * @throws IOException se la scrittura della risposta fallisce
     */
    static void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterMillis,
                                     ProblemDetailWriter problemDetailWriter, Clock clock) throws IOException {
        // Arrotondato per eccesso: per difetto il client riproverebbe troppo presto e riceverebbe un altro 429
        long retryAfterSeconds = Math.max(1, Math.ceilDiv(retryAfterMillis, 1000));
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please retry after " + retryAfterSeconds + " seconds."
        );
        problemDetail.setType(ProblemTypes.RATE_LIMIT_EXCEEDED);
        problemDetail.setTitle("Too Many Requests");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", clock.instant());
        problemDetail.setProperty("retryAfterSeconds", retryAfterSeconds);

        problemDetailWriter.write(response, problemDetail);
    }
}
