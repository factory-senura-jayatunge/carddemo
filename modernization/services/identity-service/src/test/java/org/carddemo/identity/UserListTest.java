package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.Cursors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.JsonNode;

/** USRL (COUSR00C): user list, contract scenarios S1 to S5. */
class UserListTest extends UserAdminTestBase {

    private static final String BOTTOM = "You have reached the bottom of the page...";
    private static final String TOP = "You have reached the top of the page...";

    private static List<String> ids(Response r) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : r.body().path("items")) {
            ids.add(item.path("userId").asString());
        }
        return ids;
    }

    private static void assertPage(Response r, boolean hasNext, boolean hasPrevious, String message) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.body().path("hasNext").asBoolean()).as("hasNext").isEqualTo(hasNext);
        assertThat(r.body().path("hasPrevious").asBoolean()).as("hasPrevious").isEqualTo(hasPrevious);
        assertThat(r.text("message")).isEqualTo(message);
        assertThat(r.text("severity")).isEqualTo(message == null ? null : "info");
    }

    @Test
    @SpecItem({"BR-USRL-001", "NUM-USRL-002", "ERR-USRL-002"})
    void s1_theFirstPageListsTenUsersInUserIdOrder() {
        db.demoSeed();
        Response r = admin().get("/api/users");
        assertPage(r, false, false, BOTTOM);
        assertThat(ids(r)).containsExactlyElementsOf(IdentityFixtures.DEMO_SEED);
        JsonNode first = r.body().path("items").get(0);
        assertThat(first.path("firstName").asString()).isEqualTo("MARGARET");
        assertThat(first.path("lastName").asString()).isEqualTo("GOLD");
        assertThat(first.path("userType").asString()).isEqualTo("A");
        assertThat(first.has("password")).isFalse();
        assertThat(first.has("passwordHash")).isFalse();
    }

    @Test
    @SpecItem({"BR-USRL-002", "DEF-USRL-007", "Q-USRL-001", "DEF-USRL-001", "ERR-USRL-001"})
    void s3_startIsACaseInsensitivePrefixFilterAndNoRowIsAMessageNotAnAbend() {
        db.demoSeed();
        ApiClient c = admin();
        Response users = c.get("/api/users?start={s}", "user");
        assertPage(users, false, false, BOTTOM);
        assertThat(ids(users)).containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

        Response none = c.get("/api/users?start={s}", "zz");
        assertPage(none, false, false, "You are at the top of the page...");
        assertThat(ids(none)).isEmpty();
        assertThat(none.body().path("firstKey").isNull()).isTrue();
    }

    @Test
    @SpecItem({"BR-USRL-002"})
    void s3_likeWildcardsInThePrefixAreLiteral() {
        db.demoSeed();
        for (String id : List.of("A%B", "A_B", "A[B]", "AXB", "A\\B")) {
            db.user(id, "W", "C", 'U', "PASSWORD", false);
        }
        ApiClient c = admin();
        assertThat(ids(c.get("/api/users?start={s}", "a%"))).containsExactly("A%B");
        assertThat(ids(c.get("/api/users?start={s}", "A_"))).containsExactly("A_B");
        assertThat(ids(c.get("/api/users?start={s}", "A["))).containsExactly("A[B]");
        assertThat(ids(c.get("/api/users?start={s}", "A\\"))).containsExactly("A\\B");
    }

    @Test
    @SpecItem({"DEF-USRL-002", "DEF-USRL-011", "DEF-USRL-014", "VAL-USRL-003", "ERR-USRL-002"})
    void s4_keysetPagesForwardAndBackKeepFullPages() {
        db.user("ADMIN001", "MARGARET", "GOLD", 'A', IdentityFixtures.DEMO_PASSWORD, false);
        for (int i = 1; i <= 24; i++) {
            db.user(String.format("T%05d", i), "F", "L", 'U', "PASSWORD", false);
        }
        ApiClient c = admin();
        Response p1 = c.get("/api/users");
        assertPage(p1, true, false, null);
        assertThat(ids(p1)).hasSize(10).startsWith("ADMIN001", "T00001").endsWith("T00009");

        Response p2 = c.get("/api/users?after={k}", p1.text("lastKey"));
        assertPage(p2, true, true, null);
        assertThat(ids(p2)).hasSize(10).startsWith("T00010").endsWith("T00019");

        Response p3 = c.get("/api/users?after={k}", p2.text("lastKey"));
        assertPage(p3, false, true, BOTTOM);
        assertThat(ids(p3)).containsExactly("T00020", "T00021", "T00022", "T00023", "T00024");

        Response back2 = c.get("/api/users?before={k}", p3.text("firstKey"));
        assertPage(back2, true, true, null);
        assertThat(ids(back2)).isEqualTo(ids(p2));

        Response back1 = c.get("/api/users?before={k}", back2.text("firstKey"));
        assertPage(back1, true, false, TOP);
        assertThat(ids(back1)).isEqualTo(ids(p1));

        Response small = c.get("/api/users?size=3");
        assertThat(ids(small)).containsExactly("ADMIN001", "T00001", "T00002");
    }

    @Test
    @SpecItem({"BR-USRL-002", "DEF-USRL-015"})
    void s4_pageTokensKeepTheSearch() {
        db.user("ADMIN001", "MARGARET", "GOLD", 'A', IdentityFixtures.DEMO_PASSWORD, false);
        for (int i = 1; i <= 24; i++) {
            db.user(String.format("T%05d", i), "F", "L", 'U', "PASSWORD", false);
        }
        ApiClient c = admin();
        Response p1 = c.get("/api/users?start={s}&size=5", "t0001");
        assertPage(p1, true, false, null);
        assertThat(ids(p1)).containsExactly("T00010", "T00011", "T00012", "T00013", "T00014");

        Response p2 = c.get("/api/users?after={k}&size=5", p1.text("lastKey"));
        assertPage(p2, false, true, BOTTOM);
        assertThat(ids(p2)).containsExactly("T00015", "T00016", "T00017", "T00018", "T00019");

        Response back = c.get("/api/users?before={k}&size=5", p2.text("firstKey"));
        assertPage(back, true, false, TOP);
        assertThat(ids(back)).isEqualTo(ids(p1));
    }

    @Test
    @SpecItem({"DEF-USRL-002"})
    void s5_pageRequestErrors() {
        db.demoSeed();
        ApiClient c = admin();
        Response both = c.get("/api/users?start=a&after=x");
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.text("code")).isEqualTo("INVALID_PAGE_REQUEST");
        assertThat(c.get("/api/users?size=11").text("code")).isEqualTo("INVALID_PAGE_SIZE");
        assertThat(c.get("/api/users?size=0").text("code")).isEqualTo("INVALID_PAGE_SIZE");
        assertThat(c.get("/api/users?size=ten").text("code")).isEqualTo("INVALID_PAGE_SIZE");
        assertThat(c.get("/api/users?after={k}", "!!").text("code")).isEqualTo("INVALID_CURSOR");
        // A well-formed token whose user ID does not start with its prefix was not made here.
        assertThat(c.get("/api/users?after={k}", Cursors.encode("1:XADMIN001")).text("code"))
                .isEqualTo("INVALID_CURSOR");
        assertThat(c.get("/api/users?before={k}", Cursors.encode("ADMIN001")).text("code"))
                .isEqualTo("INVALID_CURSOR");
        // startKey counts as a fourth way to start a page.
        assertThat(c.get("/api/users?start=a&startKey=YQ").text("code")).isEqualTo("INVALID_PAGE_REQUEST");
        assertThat(c.get("/api/users?startKey=YQ&after=x").text("code")).isEqualTo("INVALID_PAGE_REQUEST");
        // Padding, a character outside base64url and non-zero trailing bits are not keys.
        for (String bad : List.of("YQ==", "Y+", "YR", "Y")) {
            assertProblem(c.get("/api/users?startKey={k}", bad), 400, "INVALID_REQUEST", "The request is not valid ...",
                    null);
        }
    }

    @Test
    @SpecItem({"BR-USRL-002"})
    void startKeyIsTheStartPrefixInKeyForm() {
        db.demoSeed();
        ApiClient c = admin();
        Response plain = c.get("/api/users?start={s}", "user000");
        // base64url of the UTF-8 bytes of "user000", no padding.
        Response keyed = c.get("/api/users?startKey={k}", "dXNlcjAwMA");
        assertPage(keyed, false, false, BOTTOM);
        assertThat(ids(keyed)).isEqualTo(ids(plain)).containsExactly("USER0001", "USER0002", "USER0003", "USER0004",
                "USER0005");
        assertThat(keyed.text("firstKey")).isEqualTo(plain.text("firstKey")).matches("[A-Za-z0-9_-]+");
        assertThat(keyed.text("lastKey")).isEqualTo(plain.text("lastKey")).matches("[A-Za-z0-9_-]+");
        // A key of a blank prefix is no search, as a blank start is.
        assertThat(ids(c.get("/api/users?startKey={k}", "ICA"))).containsExactlyElementsOf(IdentityFixtures.DEMO_SEED);
        assertProblem(c.get("/api/users?startKey={k}", "5Lit"), 400, "UNSUPPORTED_CHARACTER",
                "User ID contains a character that is not supported.", "start");
    }

    @Test
    @SpecItem({"BR-USRL-002"})
    void startFollowsTheUserIdTextRules() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(c.get("/api/users?start={s}", "中"), 400, "UNSUPPORTED_CHARACTER",
                "User ID contains a character that is not supported.", "start");
        assertProblem(c.get("/api/users?start={s}", "ABCDEFGHI"), 400, "TOO_LONG",
                "User ID must not be longer than 8 characters.", "start");
    }

    @Test
    @SpecItem({"ERR-USRL-001"})
    void aDatabaseFailureGivesTheLookupText() {
        db.demoSeed();
        ApiClient c = admin();
        doThrow(new DataAccessResourceFailureException("down"))
                .when(userRepository).list(anyString(), any(), any(), anyInt());
        assertProblem(c.get("/api/users"), 500, "USER_LOOKUP_FAILED", "Unable to lookup User...", "start");
    }
}
