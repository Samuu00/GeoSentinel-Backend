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

public class RateLimitFilter extends OncePerRequestFilter {
    private static final String AUTH_PATH = "/api/v1/auth";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private final RedisRateLimiter redisRateLimiter;
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;
    // fail-closed su /auth/**: se Redis è giù, login e refresh restano comunque limitati
    private final RateLimitPolicy loginPolicy;
    private final RateLimitPolicy refreshPolicy;
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

    // Un SOS non riceve mai un 429. L'abuso resta limitato: raiseSos restituisce l'allerta già attiva invece di crearne un'altra
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/v1/sos".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        RateLimitPolicy policy;
        String prefix;
        // Un bucket per policy: con una chiave condivisa le GET pubbliche ricaricherebbero il bucket del login,
        // e i refresh (frequenti sotto CGNAT) consumerebbero i tentativi di login
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

        // chiave per utente se autenticato, altrimenti per IP (getRemoteAddr: X-Forwarded-For solo dietro proxy fidato).
        // Il filtro gira dopo BearerTokenAuthenticationFilter: con il bearer anche le GET pubbliche contano per utente
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String rateLimitKey = (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt)
                ? prefix + "user:" + jwt.getSubject()
                : prefix + "ip:" + request.getRemoteAddr();

        RateLimitDecision decision = redisRateLimiter.tryConsume(rateLimitKey, policy);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));

        if (!decision.allowed()) {
            // arrotondato per eccesso: per difetto il client ritenterebbe troppo presto e riceverebbe un altro 429
            long retryAfterSeconds = Math.max(1, Math.ceilDiv(decision.retryAfterMillis(), 1000));
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
            return;
        }

        filterChain.doFilter(request, response);
    }
}
