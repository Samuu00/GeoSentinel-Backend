package it.unical.geosentinel.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

// Scrive body RFC 7807 dai filtri servlet (sicurezza, rate limiting), dove @RestControllerAdvice non arriva.
@Component
public class ProblemDetailWriter {
    private final JsonMapper jsonMapper;

    public ProblemDetailWriter(JsonMapper jsonMapper) {
        // il mixin porta ProblemDetail#properties (errors, traceId, ...) al primo livello del JSON, come richiede RFC 7807
        this.jsonMapper = jsonMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    public void write(HttpServletResponse response, ProblemDetail problemDetail) throws IOException {
        // come GlobalExceptionHandler.enrich: l'observation filter di Boot gira prima della catena di sicurezza,
        // quindi l'MDC ha già il traceId anche per i 401/403/429 scritti dai filtri
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }
        response.setStatus(problemDetail.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getWriter(), problemDetail);
    }
}
