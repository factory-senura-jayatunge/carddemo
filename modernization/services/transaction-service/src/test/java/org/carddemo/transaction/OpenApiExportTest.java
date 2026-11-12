package org.carddemo.transaction;

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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The web client is generated from the committed snapshot, so a contract change must show up as a
 * reviewed diff of that file. The comparison is on the JSON value: key order and layout are free.
 * {@code -Dopenapi.update=true} writes the export over the snapshot.
 */
class OpenApiExportTest extends TransactionTestBase {

    static final Path EXPORT = Path.of("target/openapi/transaction-service.json");
    static final Path SNAPSHOT = Path.of("../../web/openapi/transaction-service.json");

    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("get /api/transactions", "listTransactions"),
            Map.entry("get /api/transactions/{tranId}", "viewTransaction"),
            Map.entry("post /api/transactions/{tranId}/unmask", "unmaskTransaction"),
            Map.entry("post /api/transactions/validate", "validateTransaction"),
            Map.entry("post /api/transactions", "addTransaction"),
            Map.entry("get /api/transactions/latest", "latestTransaction"),
            Map.entry("post /api/reports/transactions", "requestTransactionReport"),
            Map.entry("get /api/reports/transactions/{runId}", "getTransactionReport"),
            Map.entry("get /api/reports/transactions/{runId}/file", "downloadTransactionReport"));

    @Test
    void theExportMatchesTheCommittedSnapshot() throws Exception {
        String export = export();
        Files.createDirectories(EXPORT.getParent());
        Files.writeString(EXPORT, export.endsWith("\n") ? export : export + "\n");
        if (Boolean.getBoolean("openapi.update")) {
            Files.copy(EXPORT, SNAPSHOT, StandardCopyOption.REPLACE_EXISTING);
        }

        assertThat(SNAPSHOT).as("snapshot %s; copy %s there", SNAPSHOT.toAbsolutePath().normalize(), EXPORT).exists();
        ObjectMapper mapper = new ObjectMapper();
        List<String> differences = new ArrayList<>();
        diff("$", mapper.readTree(SNAPSHOT.toFile()), mapper.readTree(export), differences);
        assertThat(differences)
                .as("export %s differs from snapshot %s; review the change and rerun with -Dopenapi.update=true", EXPORT, SNAPSHOT)
                .isEmpty();
    }

    @Test
    void theOperationsAreTheContractsOnes() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(export()).path("paths");
        Map<String, String> found = new TreeMap<>();
        paths.fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(
                m -> found.put(m.getKey() + " " + p.getKey(), m.getValue().path("operationId").asText())));
        assertThat(found).isEqualTo(new TreeMap<>(OPERATIONS));
    }

    @Test
    void responsesCarryEveryMember() throws Exception {
        JsonNode schemas = new ObjectMapper().readTree(export()).path("components").path("schemas");
        for (String response : List.of("TransactionPage", "TransactionRow", "TransactionView", "UnmaskResponse",
                "TransactionCheck", "AddResult", "LatestTransaction", "Hint", "ReportRun")) {
            JsonNode s = schemas.path(response);
            TreeSet<String> members = new TreeSet<>();
            s.path("properties").fieldNames().forEachRemaining(members::add);
            TreeSet<String> required = new TreeSet<>();
            s.path("required").forEach(r -> required.add(r.asText()));
            assertThat(members).as(response).isNotEmpty();
            assertThat(required).as(response).isEqualTo(members);
        }
        assertThat(schemas.path("TransactionPage").path("properties").path("severity").path("anyOf").toString())
                .isEqualTo("[{\"$ref\":\"#/components/schemas/Severity\"},{\"type\":\"null\"}]");
        assertThat(schemas.path("TransactionView").path("properties").path("cardNum").path("type").asText())
                .isEqualTo("string");
        assertThat(schemas.path("UnmaskResponse").path("properties").path("fields").path("additionalProperties")
                .path("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(schemas.has("NullableString")).isFalse();
        assertThat(schemas.has("Problem")).isTrue();
    }

    private String export() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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
