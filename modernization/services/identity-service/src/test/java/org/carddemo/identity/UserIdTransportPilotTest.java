package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.identity.IdentityFixtures.OpsAuditRow;
import org.carddemo.identity.web.UserIdPathTransportProbeTest;
import org.carddemo.identity.web.UserKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract S31 over real HTTP: the embedded Tomcat on a random port, the production filter chain
 * and firewall, and the service on its database. Every hard user ID goes through add, list with
 * {@code startKey}, fetch, update, delete, re-add and sign-on in key form and must behave as an
 * ASCII ID does. The plain form gets the result the transport probe pins: success for the IDs the
 * stack lets through, {@code 400} and no change for the others.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "carddemo.identity.bcrypt-strength=4",
        "carddemo.identity.seed.enabled=false"})
class UserIdTransportPilotTest extends UserAdminTestBase {

    /** The IDs of the ruling's pilot list; each is canonical (upper case, no space inside). */
    static final List<String> HARD_IDS = List.of("A\0B", "A/B", "A%B", "A\\B", "A;B", "A?B", "A#B", "A+B",
            "A\u0001B", "A\u007fB", "A\u0085B", "A\tB", "A\u00a0B", "\u00ff", "\u00df", ".", "..");

    private static final String STALE = "\"0000000000000000\"";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    @Test
    void everyHardIdBehavesAsAnAsciiIdInKeyForm() {
        db.demoUsers();
        Http admin = signedOnOverHttp("ADMIN001");
        List<String> problems = new ArrayList<>();
        for (String id : HARD_IDS) {
            try {
                lifecycle(admin, id);
            } catch (AssertionError e) {
                problems.add(show(id) + ": " + e.getMessage());
            }
        }
        assertThat(problems).as("IDs that do not behave as an ASCII ID").isEmpty();
        // A key is transport only: the audit holds the canonical IDs.
        List<String> subjects = db.opsAudit().stream().map(OpsAuditRow::subjectKey).toList();
        assertThat(subjects).containsAll(HARD_IDS);
        assertThat(subjects).doesNotContainAnyElementsOf(HARD_IDS.stream().map(UserKeys::encode)
                .filter(k -> !HARD_IDS.contains(k)).toList());
    }

    @Test
    void anAsciiIdWorksInBothForms() {
        db.demoUsers();
        Http admin = signedOnOverHttp("ADMIN001");
        Res me = admin.send("GET", "/api/users/ADMIN001", null, null);
        assertThat(me.status()).isEqualTo(200);
        Res byKey = admin.send("GET", "/api/users/key/" + UserKeys.encode("ADMIN001"), null, null);
        assertThat(byKey.status()).isEqualTo(200);
        assertThat(byKey.body()).isEqualTo(me.body());
        assertThat(admin.send("GET", "/api/users?startKey=" + UserKeys.encode("ADMIN001"), null, null).body()
                .path("items").get(0).path("userId").asString()).isEqualTo("ADMIN001");
        Res self = admin.send("DELETE", "/api/users/key/" + UserKeys.encode("ADMIN001"), null, me.etag());
        assertThat(self.status()).isEqualTo(409);
        assertThat(self.text("code")).isEqualTo("CANNOT_DELETE_SELF");

        // PLAIN001 through the plain form only, delete included.
        Res add = admin.send("POST", "/api/users", newUser("PLAIN", "USER", "plain001", "PASSWORD", "U"), null);
        assertThat(add.status()).as("%s", add.body()).isEqualTo(201);
        Res got = admin.send("GET", "/api/users/PLAIN001", null, null);
        assertThat(got.status()).isEqualTo(200);
        Res put = admin.send("PUT", "/api/users/PLAIN001", change("PLAIN", "CHANGED", "U", null), got.etag());
        assertThat(put.status()).as("%s", put.body()).isEqualTo(200);
        Res del = admin.send("DELETE", "/api/users/PLAIN001", null, put.etag());
        assertThat(del.status()).as("%s", del.body()).isEqualTo(200);
        assertThat(admin.send("GET", "/api/users/PLAIN001", null, null).status()).isEqualTo(404);
        assertThat(admin.send("POST", "/api/users", newUser("PLAIN", "AGAIN", "PLAIN001", "PASSWORD", "U"), null)
                .status()).isEqualTo(201);
        // The segment KEY alone is a user ID, not the key form.
        assertThat(admin.send("GET", "/api/users/key", null, null).text("code")).isEqualTo("USER_ID_NOT_FOUND");
    }

    @Test
    void aBadKeyIsAnInvalidRequestAndChangesNothing() {
        db.demoUsers();
        Http admin = signedOnOverHttp("ADMIN001");
        String etag = admin.send("GET", "/api/users/USER0001", null, null).etag();
        for (String bad : List.of("VVNFUjAwMDE=", "VVNFUjAwMDE%3D", "VVNFUjAwMDF", "_w", "A")) {
            for (String method : List.of("GET", "PUT", "DELETE")) {
                Res r = admin.send(method, "/api/users/key/" + bad,
                        method.equals("PUT") ? change("X", "Y", "U", null) : null, etag);
                assertThat(r.status()).as("%s %s: %s", method, bad, r.body()).isEqualTo(400);
                assertThat(r.text("code")).as("%s %s", method, bad).isEqualTo("INVALID_REQUEST");
            }
        }
        assertThat(admin.send("GET", "/api/users/USER0001", null, null).etag()).isEqualTo(etag);
        assertThat(db.opsAudit()).isEmpty();
    }

    private void lifecycle(Http admin, String id) {
        String key = "/api/users/key/" + UserKeys.encode(id);
        String plain = "/api/users/" + UserIdPathTransportProbeTest.encodeUriComponent(id);
        boolean plainRefused = UserIdPathTransportProbeTest.PLAIN_REFUSED.contains(id);

        Res add = admin.send("POST", "/api/users", newUser("PILOT", "USER", id, "PASSWORD", "U"), null);
        assertThat(add.status()).as("add %s", add.body()).isEqualTo(201);
        assertThat(add.body().path("user").path("userId").asString()).isEqualTo(id);
        assertThat(add.location()).isEqualTo(key);

        Res list = admin.send("GET", "/api/users?startKey=" + UserKeys.encode(id), null, null);
        assertThat(list.status()).as("list %s", list.body()).isEqualTo(200);
        assertThat(list.body().path("items").get(0).path("userId").asString()).as("list").isEqualTo(id);

        Res got = admin.send("GET", key, null, null);
        assertThat(got.status()).as("fetch %s", got.body()).isEqualTo(200);
        assertThat(got.text("userId")).isEqualTo(id);
        String etag = got.etag();

        Res plainGet = admin.send("GET", plain, null, null);
        Res plainPut = admin.send("PUT", plain, change("PILOT", "PLAIN", "U", null), etag);
        if (plainRefused) {
            assertThat(plainGet.status()).as("plain fetch").isEqualTo(400);
            assertThat(plainPut.status()).as("plain save").isEqualTo(400);
            Res after = admin.send("GET", key, null, null);
            assertThat(after.etag()).as("refused plain save changed the row").isEqualTo(etag);
            assertThat(after.text("lastName")).isEqualTo("USER");
        } else {
            assertThat(plainGet.status()).as("plain fetch %s", plainGet.body()).isEqualTo(200);
            assertThat(plainGet.text("userId")).isEqualTo(id);
            assertThat(plainPut.status()).as("plain save %s", plainPut.body()).isEqualTo(200);
            etag = plainPut.etag();
        }

        Res put = admin.send("PUT", key, change("PILOT", "KEYFORM", "U", null), etag);
        assertThat(put.status()).as("save %s", put.body()).isEqualTo(200);
        assertThat(put.body().path("user").path("lastName").asString()).isEqualTo("KEYFORM");
        assertThat(put.text("message")).isEqualTo("User " + id + " has been updated ...");
        etag = put.etag();

        // A stale version reaches the controller as RECORD_CHANGED; a refused segment never does.
        Res plainDelete = admin.send("DELETE", plain, null, STALE);
        assertThat(plainDelete.status()).as("plain delete %s", plainDelete.body()).isEqualTo(plainRefused ? 400 : 409);
        assertThat(admin.send("GET", key, null, null).etag()).as("plain delete changed the row").isEqualTo(etag);

        Http user = signedOnOverHttp(id);
        assertThat(user.send("GET", "/api/auth/me", null, null).text("userId")).isEqualTo(id);

        Res del = admin.send("DELETE", key, null, etag);
        assertThat(del.status()).as("delete %s", del.body()).isEqualTo(200);
        assertThat(del.text("message")).isEqualTo("User " + id + " has been deleted ...");
        assertThat(admin.send("GET", key, null, null).text("code")).isEqualTo("USER_ID_NOT_FOUND");
        assertThat(user.send("GET", "/api/auth/me", null, null).status()).as("session after delete").isEqualTo(401);

        Res again = admin.send("POST", "/api/users", newUser("PILOT", "AGAIN", id, "PASSWORD", "U"), null);
        assertThat(again.status()).as("re-add %s", again.body()).isEqualTo(201);
        assertThat(admin.send("GET", key, null, null).text("lastName")).isEqualTo("AGAIN");
    }

    private Http signedOnOverHttp(String userId) {
        Http c = new Http();
        Res r = c.send("POST", "/api/auth/sign-on", ApiClient.json(Map.of("userId", userId, "password",
                IdentityFixtures.DEMO_PASSWORD)), null);
        assertThat(r.status()).as("sign-on of %s: %s", show(userId), r.body()).isEqualTo(200);
        return c;
    }

    private static String show(String id) {
        return "[" + UserIdPathTransportProbeTest.encodeUriComponent(id) + "]";
    }

    record Res(int status, JsonNode body, HttpResponse<String> raw) {

        String text(String field) {
            JsonNode n = body.path(field);
            return n.isNull() || n.isMissingNode() ? null : n.asString();
        }

        String etag() {
            return raw.headers().firstValue("ETag").orElse(null);
        }

        String location() {
            return raw.headers().firstValue("Location").orElse(null);
        }
    }

    /** A browser-like client over the socket: cookies by path, X-XSRF-TOKEN on unsafe calls. */
    final class Http {

        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, String[]> jar = new LinkedHashMap<>();

        Res send(String method, String rawPathAndQuery, String body, String ifMatch) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPathAndQuery));
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            b.method(method, publisher);
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            if (ifMatch != null) {
                b.header("If-Match", ifMatch);
            }
            String path = rawPathAndQuery.split("\\?", 2)[0];
            List<String> cookies = new ArrayList<>();
            jar.forEach((name, v) -> {
                if (path.startsWith(v[1])) {
                    cookies.add(name + "=" + v[0]);
                }
            });
            if (!cookies.isEmpty()) {
                b.header("Cookie", String.join("; ", cookies));
            }
            if (!method.equals("GET") && jar.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN")[0]);
            }
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                for (String header : r.headers().allValues("Set-Cookie")) {
                    ApiClient.SetCookie c = ApiClient.parse(header);
                    if ("0".equals(c.attr("Max-Age"))) {
                        jar.remove(c.name());
                    } else {
                        jar.put(c.name(), new String[] {c.value(), c.attr("Path") == null ? "/" : c.attr("Path")});
                    }
                }
                String content = r.body() == null ? "" : r.body();
                JsonNode json;
                try {
                    json = content.isBlank() ? JSON.missingNode() : JSON.readTree(content);
                } catch (RuntimeException notJson) {
                    // Tomcat's own 400 page for a refused path is HTML.
                    json = JSON.missingNode();
                }
                return new Res(r.statusCode(), json, r);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
