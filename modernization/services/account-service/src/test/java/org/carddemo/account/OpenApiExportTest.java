package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The web client is generated from the committed snapshot, so a contract change must show up as a
 * reviewed diff of that file. The comparison is on the JSON value: key order and layout are free.
 * {@code -Dopenapi.update=true} writes the export over the snapshot.
 */
class OpenApiExportTest extends AccountTestBase {

    static final Path EXPORT = Path.of("target/openapi/account-service.json");
    static final Path SNAPSHOT = Path.of("../../web/openapi/account-service.json");

    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("get /api/accounts/{acctId}", "viewAccount"),
            Map.entry("post /api/accounts/{acctId}/unmask", "unmaskAccount"),
            Map.entry("get /api/accounts/{acctId}/update-form", "fetchAccountForUpdate"),
            Map.entry("post /api/accounts/{acctId}/validate", "validateAccountUpdate"),
            Map.entry("put /api/accounts/{acctId}", "updateAccount"),
            Map.entry("post /api/cards/search", "listCards"),
            Map.entry("post /api/cards/lookup", "lookupCard"),
            Map.entry("get /api/cards/{cardId}", "viewCard"),
            Map.entry("post /api/cards/{cardId}/unmask", "unmaskCard"),
            Map.entry("post /api/cards/update-lookup", "lookupCardForUpdate"),
            Map.entry("get /api/cards/{cardId}/update-form", "fetchCardForUpdate"),
            Map.entry("post /api/cards/{cardId}/validate", "validateCardUpdate"),
            Map.entry("put /api/cards/{cardId}", "updateCard"));

    @Test
    void theExportMatchesTheCommittedSnapshot() throws Exception {
        String json = export();
        Files.createDirectories(EXPORT.getParent());
        Files.writeString(EXPORT, json.endsWith("\n") ? json : json + "\n");
        if (Boolean.getBoolean("openapi.update")) {
            Files.copy(EXPORT, SNAPSHOT, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
    void theOperationsAreTheContractsOnes() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(export()).path("paths");
        Map<String, String> found = new java.util.TreeMap<>();
        paths.fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(
                m -> found.put(m.getKey() + " " + p.getKey(), m.getValue().path("operationId").asText())));
        assertThat(found).isEqualTo(new java.util.TreeMap<>(OPERATIONS));
    }

    @Test
    void responsesCarryEveryMemberAndRequestsMayLeaveThemOut() throws Exception {
        JsonNode schemas = new ObjectMapper().readTree(export()).path("components").path("schemas");
        for (String response : List.of("AccountView", "CustomerView", "CrossReference", "AccountUpdateForm", "CardPage",
                "CardRow", "CardView", "CardUpdateForm")) {
            JsonNode s = schemas.path(response);
            TreeSet<String> members = new TreeSet<>();
            s.path("properties").fieldNames().forEachRemaining(members::add);
            TreeSet<String> required = new TreeSet<>();
            s.path("required").forEach(r -> required.add(r.asText()));
            assertThat(required).as(response).isEqualTo(members);
        }
        assertThat(schemas.path("CardSearchRequest").has("required")).isFalse();
        assertThat(schemas.path("AccountView").path("properties").path("customer").path("anyOf").toString())
                .isEqualTo("[{\"$ref\":\"#/components/schemas/CustomerView\"},{\"type\":\"null\"}]");
        assertThat(schemas.path("CardPage").path("properties").path("severity").path("anyOf").toString())
                .isEqualTo("[{\"$ref\":\"#/components/schemas/Severity\"},{\"type\":\"null\"}]");
        assertThat(schemas.path("UnmaskResponse").path("properties").path("fields").path("additionalProperties")
                .path("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(schemas.has("NullableString")).isFalse();
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
