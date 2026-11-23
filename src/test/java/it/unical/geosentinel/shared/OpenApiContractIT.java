// Test di integrazione del contratto OpenAPI, con l'applicazione avviata su PostGIS e Redis in Testcontainers.
// Il client dell'app mobile viene generato dal file docs/openapi.json: questo test fallisce se le API cambiano
// senza che il file venga aggiornato. Dopo una modifica voluta alle API si rigenera il file lanciando
// "./mvnw verify -Dit.test=OpenApiContractIT -Dopenapi.update=true" e lo si aggiunge al commit.
// Il test controlla anche due regole che rendono il client generato stabile e corretto.
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

/**
 * Confronta il documento OpenAPI generato da springdoc con lo snapshot versionato e ne controlla gli
 * operationId e i parametri di query.
 */
class OpenApiContractIT extends AbstractIntegrationTest {

    /** Percorso dello snapshot del contratto, relativo alla radice del progetto. */
    private static final Path SNAPSHOT = Path.of("docs", "openapi.json");

    /**
     * Il contratto generato deve coincidere carattere per carattere con lo snapshot. Se la proprietà di sistema
     * {@code openapi.update} è attiva, il test invece riscrive lo snapshot con il contratto attuale e termina.
     */
    @Test
    void committedSnapshotMatchesGeneratedContract() throws Exception {
        String generated = generatedContract();

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(SNAPSHOT, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SNAPSHOT).as("docs/openapi.json missing: run with -Dopenapi.update=true").exists();
        // Su Windows, con core.autocrlf attivo, git può convertire i fine riga del file in CRLF al checkout:
        // si riportano a LF prima del confronto.
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(generated)
                .as("API contract changed: run with -Dopenapi.update=true and commit docs/openapi.json")
                .isEqualTo(committed);
    }

    /**
     * Ogni operazione deve avere un operationId esplicito, unico e senza suffisso numerico. Un nome come
     * {@code create_4} è generato da springdoc per risolvere un conflitto: nel client diventerebbe
     * {@code create4()} e cambierebbe nome ogni volta che si aggiunge un controller.
     */
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

    /**
     * Nessun parametro di query deve essere un oggetto. Ad esempio un {@code Pageable} senza
     * {@code @ParameterObject} diventerebbe nel client un unico parametro {@code ?pageable=...}, mentre Spring
     * legge {@code page}, {@code size} e {@code sort}: la paginazione verrebbe ignorata senza alcun errore.
     */
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

    /**
     * Scarica il documento OpenAPI dall'applicazione e lo prepara per il confronto. Toglie la sezione
     * {@code servers}, che contiene la porta casuale del test; le chiavi sono già ordinate grazie alla proprietà
     * {@code springdoc.writer-with-order-by-keys}. Il pretty printer usa il separatore di riga del sistema
     * (CRLF su Windows, LF in CI), quindi si usa sempre LF, come stabilito in {@code .gitattributes}.
     *
     * @return il contratto formattato, con LF finale
     */
    private String generatedContract() {
        ObjectNode doc = (ObjectNode) json(call(HttpMethod.GET, "/v3/api-docs", null, null).body());
        doc.remove("servers");
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc).replace("\r\n", "\n") + "\n";
    }
}
