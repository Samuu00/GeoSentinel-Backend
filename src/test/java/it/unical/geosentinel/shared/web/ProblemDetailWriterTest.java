package it.unical.geosentinel.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProblemDetailWriterTest {

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
        assertEquals("abc", body.get("traceId").asString()); // portato al primo livello dal mixin
        assertFalse(body.has("properties"));
    }
}
