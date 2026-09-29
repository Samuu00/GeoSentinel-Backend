package it.unical.geosentinel.shared;

import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// M7.1: il client Kotlin si genera da docs/openapi.json. Il test fallisce se il contratto cambia senza aggiornare il
// file: dopo una modifica voluta alle API, `./mvnw verify -Dit.test=OpenApiContractIT -Dopenapi.update=true` e commit
class OpenApiContractIT extends AbstractIntegrationTest {

    private static final Path SNAPSHOT = Path.of("docs", "openapi.json");

    @Test
    void committedSnapshotMatchesGeneratedContract() throws Exception {
        String generated = generatedContract();

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(SNAPSHOT, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SNAPSHOT).as("docs/openapi.json missing: run with -Dopenapi.update=true").exists();
        // \r\n: con core.autocrlf il checkout su Windows può convertire il file
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(generated)
                .as("API contract changed: run with -Dopenapi.update=true and commit docs/openapi.json")
                .isEqualTo(committed);
    }

    // Un operationId con suffisso numerico (create_4) è un nome generato da springdoc: nel client diventa create4()
    // e cambia quando si aggiunge un controller
    @Test
    void everyOperationHasStableExplicitId() {
        JsonNode paths = json(call(HttpMethod.GET, "/v3/api-docs", null, null).body()).get("paths");
        List<String> ids = new ArrayList<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(op -> {
            JsonNode id = op.getValue().get("operationId");
            assertThat(id).as("operationId of %s %s", op.getKey(), path.getKey()).isNotNull();
            ids.add(id.asString());
        }));

        assertThat(ids).isNotEmpty().doesNotHaveDuplicates().noneMatch(id -> id.matches(".*_\\d+$"));
    }

    // Un oggetto in query (Pageable senza @ParameterObject) diventa ?pageable=... nel client generato, mentre Spring
    // legge page/size/sort: la paginazione verrebbe ignorata in silenzio
    @Test
    void queryParametersAreScalars() {
        JsonNode paths = json(call(HttpMethod.GET, "/v3/api-docs", null, null).body()).get("paths");
        List<String> objectParams = new ArrayList<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(op -> {
            JsonNode params = op.getValue().get("parameters");
            if (params != null) {
                params.forEach(p -> {
                    if ("query".equals(p.get("in").asString()) && p.get("schema").has("$ref")) {
                        objectParams.add(op.getValue().get("operationId").asString() + "." + p.get("name").asString());
                    }
                });
            }
        }));

        assertThat(objectParams).isEmpty();
    }

    // servers contiene la porta casuale del test; le chiavi sono già ordinate (springdoc.writer-with-order-by-keys).
    // Il pretty printer usa System.lineSeparator(): su Windows \r\n, in CI \n. Sempre \n, come in .gitattributes
    private String generatedContract() {
        ObjectNode doc = (ObjectNode) json(call(HttpMethod.GET, "/v3/api-docs", null, null).body());
        doc.remove("servers");
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc).replace("\r\n", "\n") + "\n";
    }
}
