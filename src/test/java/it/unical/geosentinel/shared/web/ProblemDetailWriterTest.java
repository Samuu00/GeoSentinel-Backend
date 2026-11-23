// Test unitario di ProblemDetailWriter, il componente che scrive gli errori in formato RFC 7807
// (application/problem+json) direttamente sulla risposta HTTP, ad esempio dai filtri di sicurezza che
// lavorano fuori dai controller. Usa una MockHttpServletResponse, senza avviare Spring.
package it.unical.geosentinel.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifica stato, content type e forma del corpo JSON prodotti da {@link ProblemDetailWriter}.
 */
class ProblemDetailWriterTest {

    /**
     * Scrive un errore 429 con una proprietà aggiuntiva ({@code traceId}). Il risultato atteso è uno stato 429,
     * il content type {@code application/problem+json} e la proprietà al primo livello del JSON, non annidata
     * in un oggetto {@code properties}.
     */
    @Test
    void writesFlatProblemJson() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "slow down");
        problem.setProperty("traceId", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemDetailWriter(json).write(response, problem);

        assertEquals(429, response.getStatus());
        assertEquals("application/problem+json;charset=UTF-8", response.getContentType());
        JsonNode body = json.readTree(response.getContentAsString());
        // Il mixin di Jackson porta le proprietà extra al primo livello, come nelle risposte dei controller.
        assertEquals("abc", body.get("traceId").asString());
        assertFalse(body.has("properties"));
    }
}
