// Componente che scrive una risposta di errore RFC 7807 direttamente sulla HttpServletResponse.
// Serve ai filtri servlet (catena di Spring Security, rate limiting), che lavorano prima dei controller
// e quindi non passano da GlobalExceptionHandler: così anche i loro 401, 403 e 429 hanno lo stesso formato.
package it.unical.geosentinel.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Serializza un {@link ProblemDetail} in JSON ({@code application/problem+json}) per i filtri servlet,
 * aggiungendo il {@code traceId} come fa {@link GlobalExceptionHandler}.
 */
@Component
public class ProblemDetailWriter {
    /** Mapper JSON con il mixin di Spring per ProblemDetail. */
    private final JsonMapper jsonMapper;

    /**
     * Ricava dal mapper dell'applicazione una copia con il mixin {@link ProblemDetailJacksonMixin}.
     * Il mixin porta le proprietà aggiuntive del ProblemDetail (errors, traceId e simili) al primo
     * livello del JSON, come prevede la RFC 7807, invece di annidarle in un oggetto {@code properties}.
     *
     * @param jsonMapper mapper Jackson 3 configurato da Spring Boot
     */
    public ProblemDetailWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    /**
     * Scrive il ProblemDetail come corpo della risposta, impostando status, content type e codifica.
     *
     * @param response      risposta HTTP ancora da scrivere
     * @param problemDetail errore da inviare
     * @throws IOException se la scrittura sul writer della risposta fallisce
     */
    public void write(HttpServletResponse response, ProblemDetail problemDetail) throws IOException {
        // Il filtro di osservabilità di Spring Boot gira prima della catena di sicurezza, quindi l'MDC
        // contiene già il traceId anche per gli errori scritti dai filtri.
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
