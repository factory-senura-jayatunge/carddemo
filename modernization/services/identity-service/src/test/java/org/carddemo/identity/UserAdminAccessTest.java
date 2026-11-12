package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.web.UserKeys;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;

/** Who may call /api/users, CSRF, the request body and the batch window: scenarios S2, S15 and S30. */
class UserAdminAccessTest extends UserAdminTestBase {

    private static final String BATCH_TEXT = "User changes are not available during the batch cycle. Try again later...";

    @Test
    @SpecItem({"DEF-USRL-008", "DEF-USRA-005", "DEF-USRU-006", "DEF-USRD-004"})
    void s2_onlyAdministratorsMayCall() {
        db.demoSeed();
        Response anonymous = client().get("/api/users");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.text("code")).isEqualTo("UNAUTHENTICATED");

        ApiClient user = signedOn("USER0001");
        for (Response r : new Response[] {
                user.get("/api/users"),
                user.get("/api/users/USER0001"),
                user.post("/api/users", newUser("A", "B", "X1", "secret", "U")),
                user.put("/api/users/USER0002", change("A", "B", "U", null), "\"0000000000000000\""),
                user.delete("/api/users/USER0002", "\"0000000000000000\"")}) {
            assertThat(r.status()).as("%s", r.body()).isEqualTo(403);
            assertThat(r.text("code")).isEqualTo("ACCESS_DENIED");
        }
        assertThat(db.opsAudit()).isEmpty();
    }

    @Test
    @SpecItem({"DEF-USRA-005"})
    void s30_unsafeCallsNeedTheCsrfHeader() {
        db.demoSeed();
        ApiClient c = admin().withoutCsrfHeader();
        Response r = c.post("/api/users", newUser("A", "B", "X1", "secret", "U"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.text("code")).isEqualTo("CSRF_REJECTED");

        String etag = etag(c, "USER0001");
        String key = "/api/users/key/" + UserKeys.encode("USER0001");
        for (Response refused : List.of(
                c.put("/api/users/USER0001", change("X", "Y", "U", null), etag),
                c.put(key, change("X", "Y", "U", null), etag),
                c.delete("/api/users/USER0001", etag),
                c.delete(key, etag))) {
            assertThat(refused.status()).as("%s", refused.body()).isEqualTo(403);
            assertThat(refused.text("code")).isEqualTo("CSRF_REJECTED");
        }
        assertThat(etag(c, "USER0001")).isEqualTo(etag);
        assertThat(db.opsAudit()).isEmpty();
    }

    /** The rule every service follows, through identity's own filter chain. */
    @Test
    @SpecItem({"DEF-USRA-005"})
    void s30_theCookieNeedsTheHeaderAndABearerTokenAloneDoesNot() {
        db.demoSeed();
        ApiClient c = admin();
        String token = c.cookieValue("cd_at");
        assertThat(token).isNotBlank();

        c.withoutCsrfHeader();
        Response cookieOnly = c.put("/api/users/USER0001", change("A", "B", "U", null), etag(c, "USER0001"));
        assertThat(cookieOnly.status()).as("%s", cookieOnly.body()).isEqualTo(403);
        assertThat(cookieOnly.text("code")).isEqualTo("CSRF_REJECTED");
        c.withBearer(token);
        Response both = c.put("/api/users/USER0001", change("A", "B", "U", null), etag(c, "USER0001"));
        assertThat(both.status()).as("cookie and bearer: %s", both.body()).isEqualTo(403);
        assertThat(both.text("code")).isEqualTo("CSRF_REJECTED");
        assertThat(db.opsAudit()).isEmpty();

        c.withCsrfHeader().withBearer(null);
        Response withHeader = c.put("/api/users/USER0001", change("A", "B", "U", null), etag(c, "USER0001"));
        assertThat(withHeader.status()).as("%s", withHeader.body()).isEqualTo(200);

        ApiClient script = client().withoutCsrfHeader().withBearer(token);
        Response bearerOnly = script.put("/api/users/USER0001", change("C", "D", "U", null), etag(c, "USER0001"));
        assertThat(bearerOnly.status()).as("bearer only: %s", bearerOnly.body()).isEqualTo(200);
        assertThat(db.adminRow("USER0001").firstName()).isEqualTo("C");
    }

    @Test
    @SpecItem({"VAL-USRA-001"})
    void aBodyThatIsNotTheFormIsAnInvalidRequest() {
        db.demoSeed();
        ApiClient c = admin();
        for (Response r : new Response[] {c.post("/api/users", "not json"), c.post("/api/users", null),
                c.put("/api/users/USER0001", "[1]", etag(c, "USER0001"))}) {
            assertThat(r.status()).as("%s", r.body()).isEqualTo(400);
            assertThat(r.text("code")).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    @SpecItem({"Q-USRA-002"})
    void s15_writesWaitForTheBatchWindowAndReadsDoNot() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        db.openBatchWindow();
        for (Response r : new Response[] {
                c.post("/api/users", newUser("A", "B", "X1", "secret", "U")),
                c.put("/api/users/USER0001", change("L", "T", "U", null), etag),
                c.delete("/api/users/USER0002", etag)}) {
            assertProblem(r, 503, "BATCH_WINDOW", BATCH_TEXT, null);
            assertThat(r.header("Retry-After")).isEqualTo("300");
        }
        assertThat(c.get("/api/users").status()).isEqualTo(200);
        assertThat(c.get("/api/users/USER0001").status()).isEqualTo(200);
        assertThat(db.opsAudit()).isEmpty();
        assertThat(db.adminRow("USER0001").firstName()).isEqualTo("LAWRENCE");
        assertThat(db.adminRow("USER0002").deletedAt()).isNull();
        assertThat(client().signOn("USER0001", "PASSWORD").status()).as("sign-on ignores the window").isEqualTo(200);
    }
}
