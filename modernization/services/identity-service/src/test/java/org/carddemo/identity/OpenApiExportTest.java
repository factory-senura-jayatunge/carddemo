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
                "jwks", "features", "listUsers", "addUser", "getUser", "updateUser", "deleteUser", "getUserByKey",
                "updateUserByKey", "deleteUserByKey");

        JsonNode schemes = exported.path("components").path("securitySchemes");
        assertThat(schemes.path("sessionCookie").path("in").asString()).isEqualTo("cookie");
        assertThat(schemes.path("sessionCookie").path("name").asString()).isEqualTo("cd_at");
        assertThat(schemes.path("csrfHeader").path("name").asString()).isEqualTo("X-XSRF-TOKEN");
        assertThat(schemes.path("bearerToken").path("scheme").asString()).isEqualTo("bearer");
        for (String path : List.of("/api/users", "/api/users/{userId}", "/api/users/key/{userKey}")) {
            exported.path("paths").path(path).properties().forEach(e -> {
                String where = e.getKey() + " " + path;
                JsonNode op = e.getValue();
                boolean safe = e.getKey().equals("get");
                assertThat(op.path("responses").path("401").path("description").asString()).as(where)
                        .isEqualTo("UNAUTHENTICATED");
                assertThat(op.path("responses").path("403").path("description").asString()).as(where)
                        .contains("ACCESS_DENIED");
                assertThat(op.path("responses").path("403").path("description").asString().contains("CSRF_REJECTED"))
                        .as(where).isEqualTo(!safe);
                assertThat(op.path("security").toString()).as(where).isEqualTo(safe
                        ? "[{\"sessionCookie\":[]},{\"bearerToken\":[]}]"
                        : "[{\"csrfHeader\":[],\"sessionCookie\":[]},{\"bearerToken\":[]}]");
            });
        }
        assertThat(schemes.path("refreshCookie").path("in").asString()).isEqualTo("cookie");
        assertThat(schemes.path("refreshCookie").path("name").asString()).isEqualTo("cd_rt");
        JsonNode auth = exported.path("paths");
        assertThat(auth.path("/api/auth/refresh").path("post").path("security").toString())
                .isEqualTo("[{\"csrfHeader\":[],\"refreshCookie\":[]}]");
        assertThat(auth.path("/api/auth/sign-off").path("post").path("security").toString())
                .isEqualTo("[{\"csrfHeader\":[],\"refreshCookie\":[]},{\"csrfHeader\":[],\"sessionCookie\":[]}]");
        for (String path : List.of("/api/auth/refresh", "/api/auth/sign-off")) {
            assertThat(auth.path(path).path("post").path("responses").path("403").path("description").asString())
                    .as(path).isEqualTo("CSRF_REJECTED");
        }
        assertThat(exported.path("paths").path("/api/auth/sign-on").path("post").has("security")).isFalse();
        assertThat(exported.path("paths").path("/api/auth/password").path("post").has("security")).isFalse();

        assumeTrue(Files.exists(SNAPSHOT), "no committed snapshot at " + SNAPSHOT.toAbsolutePath().normalize());
        assertThat(exported)
                .as("%s differs from %s; review the change and copy the export over the snapshot", EXPORT, SNAPSHOT)
                .isEqualTo(json.readTree(Files.readString(SNAPSHOT, StandardCharsets.UTF_8)));
    }
}
