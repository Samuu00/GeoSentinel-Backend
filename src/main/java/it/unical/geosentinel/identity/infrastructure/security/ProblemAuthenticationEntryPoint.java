package it.unical.geosentinel.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.unical.geosentinel.shared.web.ProblemDetailWriter;
import it.unical.geosentinel.shared.web.ProblemTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;

@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ProblemDetailWriter problemDetailWriter;
    private final Clock clock;

    public ProblemAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter, Clock clock) {
        this.problemDetailWriter = problemDetailWriter;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");

        // messaggio generico: il motivo preciso (firma, scadenza, jti revocato) aiuterebbe solo chi attacca
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Full authentication is required to access this resource");

        problemDetail.setType(ProblemTypes.UNAUTHORIZED);
        problemDetail.setTitle("Unauthorized");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("timestamp", clock.instant());
        problemDetailWriter.write(response, problemDetail);
    }
}
