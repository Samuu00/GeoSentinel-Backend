/*
 * Punto d'ingresso di Spring Security per le richieste non autenticate: risponde 401 con un ProblemDetail
 * (RFC 9457) e con l'header WWW-Authenticate previsto dall'RFC 6750 per i token Bearer.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Risposta 401 in formato ProblemDetail.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;

    public ProblemAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter, Clock clock) {
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
    }

    /**
     * Scrive la risposta 401.
     *
     * @param request richiesta rifiutata
     * @param response risposta da scrivere
     * @param authException motivo del rifiuto, usato solo per scegliere l'header
     * @throws IOException se la scrittura della risposta fallisce
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
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
