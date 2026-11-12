package org.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The web client is generated from the committed snapshot, so a contract change must show up as a
 * reviewed diff of that file. The comparison is on the JSON value: key order and layout are free.
 * {@code -Dopenapi.update=true} writes the export over the snapshot.
 */
class OpenApiExportTest extends AuthorizationWebTestBase {

    static final Path EXPORT = Path.of("target/openapi/authorization-service.json");
    static final Path SNAPSHOT = Path.of("../../web/openapi/authorization-service.json");

    String export() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theExportMatchesTheCommittedSnapshot() throws Exception {
        String json = export();
        Files.createDirectories(EXPORT.getParent());
        Files.writeString(EXPORT, json.endsWith("\n") ? json : json + "\n");
        if (Boolean.getBoolean("openapi.update")) {
            Files.copy(EXPORT, SNAPSHOT, StandardCopyOption.REPLACE_EXISTING);
        }

        assertThat(SNAPSHOT).as("snapshot %s; copy %s there", SNAPSHOT.toAbsolutePath().normalize(), EXPORT).exists();
        ObjectMapper mapper = new ObjectMapper();
        List<String> differences = new ArrayList<>();
        diff("$", mapper.readTree(SNAPSHOT.toFile()), mapper.readTree(json), differences);
        assertThat(differences)
                .as("export %s differs from snapshot %s; review the change and rerun with -Dopenapi.update=true", EXPORT, SNAPSHOT)
                .isEmpty();
    }

    @Test
    void theOperationIdsAreTheContractsOnes() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(export()).path("paths");
        assertThat(paths.path("/api/accounts/{acctId}/authorizations/summary").path("get").path("operationId").asText())
                .isEqualTo("getAuthorizationSummary");
        assertThat(paths.path("/api/accounts/{acctId}/authorizations").path("get").path("operationId").asText())
                .isEqualTo("listAuthorizations");
        assertThat(paths.path("/api/authorizations/{authId}").path("get").path("operationId").asText())
                .isEqualTo("getAuthorization");
        assertThat(paths.path("/api/authorizations/{authId}/fraud").path("post").path("operationId").asText())
                .isEqualTo("changeAuthorizationFraud");
        List<String> names = new ArrayList<>();
        paths.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("/api/accounts/{acctId}/authorizations/summary",
                "/api/accounts/{acctId}/authorizations", "/api/authorizations/{authId}",
                "/api/authorizations/{authId}/fraud");
    }

    /** Every $ref of the document names a schema it defines. */
    @Test
    void everyReferenceResolves() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(export());
        JsonNode schemas = doc.path("components").path("schemas");
        List<String> missing = new ArrayList<>();
        refs(doc, ref -> {
            String prefix = "#/components/schemas/";
            if (!ref.startsWith(prefix) || !schemas.has(ref.substring(prefix.length()))) {
                missing.add(ref);
            }
        });
        assertThat(missing).isEmpty();
    }

    static void refs(JsonNode node, java.util.function.Consumer<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals("$ref") && e.getValue().isTextual()) {
                    out.accept(e.getValue().asText());
                } else {
                    refs(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> refs(child, out));
        }
    }

    static void diff(String path, JsonNode expected, JsonNode actual, List<String> out) {
        if (expected.isObject() && actual.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String child = path + "." + name;
                if (!actual.has(name)) {
                    out.add(child + ": missing from the export");
                } else if (!expected.has(name)) {
                    out.add(child + ": not in the snapshot: " + actual.get(name));
                } else {
                    diff(child, expected.get(name), actual.get(name), out);
                }
            }
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            Iterator<JsonNode> e = expected.elements();
            Iterator<JsonNode> a = actual.elements();
            for (int i = 0; e.hasNext(); i++) {
                diff(path + "[" + i + "]", e.next(), a.next(), out);
            }
        } else if (!expected.equals(actual)) {
            out.add(path + ": snapshot " + expected + ", export " + actual);
        }
    }
}
