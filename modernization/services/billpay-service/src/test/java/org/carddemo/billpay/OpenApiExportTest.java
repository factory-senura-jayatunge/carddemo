package org.carddemo.billpay;

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
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The web client is generated from the committed snapshot, so a contract change must show up as a
 * reviewed diff of that file. The comparison is on the JSON value: key order and layout are free.
 * {@code -Dopenapi.update=true} writes the export over the snapshot.
 */
class OpenApiExportTest extends BillPayTestBase {

    static final Path EXPORT = Path.of("target/openapi/billpay-service.json");
    static final Path SNAPSHOT = Path.of("../../web/openapi/billpay-service.json");

    @Test
    void theExportMatchesTheCommittedSnapshot() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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
    void theOperationIdsAreTheContractsOnes() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = new ObjectMapper().readTree(json).path("paths");
        assertThat(paths.path("/api/billpay/accounts/{acctId}").path("get").path("operationId").asText())
                .isEqualTo("previewBillPayment");
        assertThat(paths.path("/api/billpay/accounts/{acctId}/payments").path("post").path("operationId").asText())
                .isEqualTo("payBill");
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
