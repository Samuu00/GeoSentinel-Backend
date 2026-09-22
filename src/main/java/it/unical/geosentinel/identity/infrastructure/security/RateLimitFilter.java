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
    // fail-closed: se Redis è giù, /auth/** resta comunque protetto dal brute force
    private static final RateLimitPolicy AUTH_POLICY = RateLimitPolicy.perMinute(5).withFailClosed();
    private static final RateLimitPolicy GENERAL_POLICY = RateLimitPolicy.perMinute(60);

    private final RedisRateLimiter redisRateLimiter;
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;

    public RateLimitFilter(RedisRateLimiter redisRateLimiter, ProblemDetailWriter problemDetailWriter, Clock clock) {
        this.redisRateLimiter = redisRateLimiter;
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isAuthRoute = path.startsWith("/api/v1/auth");

        RateLimitPolicy policy = isAuthRoute ? AUTH_POLICY : GENERAL_POLICY;

        // chiave per utente se autenticato, altrimenti per IP (getRemoteAddr: X-Forwarded-For solo dietro proxy fidato)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String rateLimitKey = (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt)
                ? "rl:user:" + jwt.getSubject()
                : "rl:ip:" + request.getRemoteAddr();

        RateLimitDecision decision = redisRateLimiter.tryConsume(rateLimitKey, policy);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));

        if (!decision.allowed()) {
            long retryAfterSeconds = Math.max(1, decision.retryAfterMillis() / 1000);
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
