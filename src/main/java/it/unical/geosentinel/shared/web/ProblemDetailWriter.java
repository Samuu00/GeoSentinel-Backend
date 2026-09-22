package it.unical.geosentinel.shared.web;

import jakarta.servlet.http.HttpServletResponse;
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
        response.setStatus(problemDetail.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getWriter(), problemDetail);
    }
}
