package org.example.geosentinel.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

// Writes RFC 7807 bodies from servlet filters (security, rate limiting), where @RestControllerAdvice is not reached.
@Component
public class ProblemDetailWriter {
    private final JsonMapper jsonMapper;

    public ProblemDetailWriter(JsonMapper jsonMapper) {
        // the mixin flattens ProblemDetail#properties (errors, traceId, ...) into the top-level JSON, as RFC 7807 expects
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
