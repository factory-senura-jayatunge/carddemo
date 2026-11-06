package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the OpenAPI document to target/openapi/identity-service.json and compares it with the
 * committed snapshot the web client is generated from, so a contract change is a reviewed diff.
 */
class OpenApiExportTest extends IdentityTestBase {

    static final Path EXPORT = Path.of("target/openapi/identity-service.json");
    static final Path SNAPSHOT = Path.of("../../web/openapi/identity-service.json");

    @Test
    void exportMatchesTheSnapshot() throws Exception {
        String doc = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Files.createDirectories(EXPORT.getParent());
        Files.writeString(EXPORT, doc.endsWith("\n") ? doc : doc + "\n", StandardCharsets.UTF_8);

        JsonMapper json = JsonMapper.builder().build();
        JsonNode exported = json.readTree(doc);
        List<String> operations = new ArrayList<>();
        exported.path("paths").forEach(path -> path.forEach(op -> operations.add(op.path("operationId").asString())));
        assertThat(operations).containsExactlyInAnyOrder("signOn", "changePassword", "refresh", "signOff", "me",
                "jwks", "features");

        assumeTrue(Files.exists(SNAPSHOT), "no committed snapshot at " + SNAPSHOT.toAbsolutePath().normalize());
        assertThat(exported)
                .as("%s differs from %s; review the change and copy the export over the snapshot", EXPORT, SNAPSHOT)
                .isEqualTo(json.readTree(Files.readString(SNAPSHOT, StandardCharsets.UTF_8)));
    }
}
