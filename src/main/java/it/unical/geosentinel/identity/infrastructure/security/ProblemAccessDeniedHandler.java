/*
 * Gestore dei 403 di Spring Security: quando un utente autenticato non ha il ruolo richiesto, risponde con un
 * ProblemDetail (RFC 9457) nello stesso formato degli altri errori dell'API.
 */
package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.unical.geosentinel.shared.web.ProblemDetailWriter;
import it.unical.geosentinel.shared.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;

/**
 * Risposta 403 in formato ProblemDetail.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler{
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;

    public ProblemAccessDeniedHandler(ProblemDetailWriter problemDetailWriter, Clock clock) {
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
    }

    /**
     * Scrive la risposta 403 con tipo, titolo, percorso della richiesta e istante.
     *
     * @param request richiesta rifiutata
     * @param response risposta da scrivere
     * @param accessDeniedException eccezione di Spring Security (il dettaglio non viene esposto)
     * @throws IOException se la scrittura della risposta fallisce
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Access is denied: insufficient privileges"
        );

        problemDetail.setType(ProblemTypes.FORBIDDEN);
        problemDetail.setTitle("Access denied");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", clock.instant());
        problemDetailWriter.write(response, problemDetail);
    }
}
