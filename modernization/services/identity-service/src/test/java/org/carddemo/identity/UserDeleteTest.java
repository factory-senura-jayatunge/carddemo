package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AdminRow;
import org.carddemo.identity.IdentityFixtures.OpsAuditRow;
import org.carddemo.identity.IdentityFixtures.SessionRow;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** USRD (COUSR03C): logical delete and re-add, contract scenarios S24 to S29 and S32. */
class UserDeleteTest extends UserAdminTestBase {

    private static Response delete(ApiClient c, String userId) {
        return c.delete("/api/users/" + userId, etag(c, userId));
    }

    @Test
    @SpecItem({"DEF-USRD-004", "Q-USRD-002"})
    void s24_anAdministratorCannotDeleteItself() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(delete(c, "ADMIN001"), 409, "CANNOT_DELETE_SELF", "You cannot delete your own User ID...",
                "userId");
        assertThat(db.adminRow("ADMIN001").deletedAt()).isNull();
    }

    @Test
    @SpecItem({"BR-USRD-004", "Q-USRD-003", "DEF-USRD-001"})
    void s25_aDeletedUserKeepsItsRowAndDisappearsEverywhereElse() {
        db.demoSeed();
        ApiClient user = signedOn("USER0004");
        ApiClient c = admin();
        Response r = delete(c, "user0004");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("message")).isEqualTo("User USER0004 has been deleted ...");
        assertThat(r.text("severity")).isEqualTo("success");

        AdminRow row = db.adminRow("USER0004");
        assertThat(row.deletedAt()).isNotNull();
        assertThat(row.deletedBy()).isEqualTo("ADMIN001");
        assertThat(db.opsAudit()).containsExactly(
                new OpsAuditRow("identity-service", "ADMIN001", "DELETE", "USER", "USER0004", null));
        assertThat(db.sessions()).filteredOn(s -> s.userId().equals("USER0004"))
                .extracting(SessionRow::endReason).containsOnly("USER_REFUSED");
        assertThat(user.refresh().status()).isEqualTo(401);

        List<String> listed = new ArrayList<>();
        c.get("/api/users").body().path("items").forEach(item -> listed.add(item.path("userId").asString()));
        assertThat(listed).doesNotContain("USER0004").hasSize(9);
        assertProblem(c.get("/api/users/USER0004"), 404, "USER_ID_NOT_FOUND", "User ID NOT found...", "userId");
        assertProblem(client().signOn("USER0004", "PASSWORD"), 401, "USER_NOT_FOUND", "User not found. Try again ...",
                "userId");
        assertProblem(c.delete("/api/users/USER0004", '"' + "0000000000000000" + '"'), 404, "USER_ID_NOT_FOUND",
                "User ID NOT found...", "userId");

        // COUSR01C adds a deleted ID again; the earlier holder's session stays ended.
        assertThat(c.post("/api/users", newUser("A", "B", "USER0004", "secret", "U")).status()).isEqualTo(201);
        assertThat(user.refresh().status()).isEqualTo(401);
    }

    @Test
    @SpecItem({"Q-USRA-002", "Q-USRD-003", "BR-USRA-002"})
    void s32_reAddingADeletedIdReusesTheRowAsANewUser() {
        db.demoSeed();
        TestDatabase.shared().admin().sql("""
                UPDATE iam.app_user SET must_change_password = 1, updated_at = '2026-01-01T00:00:00',
                    updated_by = 'ADMIN002'
                WHERE user_id = 'USER0004'""").update();
        db.signOnState("USER0004", 2, LocalDateTime.parse("2099-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));
        ApiClient c = admin();
        String before = etag(c, "USER0004");
        assertThat(c.delete("/api/users/USER0004", before).status()).isEqualTo(200);

        Response added = c.post("/api/users", newUser("Zoë", "Ñúñez", "user0004", "secret", "A"));
        assertThat(added.status()).as("%s", added.body()).isEqualTo(201);
        assertThat(added.text("message")).isEqualTo("User USER0004 has been added ...");
        assertThat(added.body().path("user").path("version").asString()).isNotEqualTo(before.replace("\"", ""));

        AdminRow row = db.adminRow("USER0004");
        assertThat(row.firstName()).isEqualTo("Zoë");
        assertThat(row.lastName()).isEqualTo("Ñúñez");
        assertThat(row.userType()).isEqualTo("A");
        assertThat(row.mustChange()).isFalse();
        assertThat(row.createdBy()).isEqualTo("ADMIN001");
        assertThat(row.updatedBy()).isNull();
        assertThat(row.deletedAt()).isNull();
        assertThat(row.deletedBy()).isNull();
        IdentityFixtures.UserRow state = db.user("USER0004");
        assertThat(state.failedAttempts()).isZero();
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.lastSignOnAt()).isNull();
        assertThat(db.opsAudit()).extracting(OpsAuditRow::action).containsExactly("DELETE", "CREATE");

        Response signOn = client().signOn("USER0004", "secret");
        assertThat(signOn.status()).as("%s", signOn.body()).isEqualTo(200);
        assertProblem(c.post("/api/users", newUser("A", "B", "USER0004", "secret", "U")), 409, "USER_ID_EXISTS",
                "User ID already exist...", "firstName");
    }

    @Test
    void reAddingAUserWithoutASignOnStateRowAddsOne() {
        db.demoSeed();
        ApiClient c = admin();
        assertThat(c.delete("/api/users/USER0003", etag(c, "USER0003")).status()).isEqualTo(200);
        db.withoutSignOnState("USER0003");
        assertThat(c.post("/api/users", newUser("A", "B", "USER0003", "secret", "U")).status()).isEqualTo(201);
        assertThat(db.hasSignOnState("USER0003")).isTrue();
        assertThat(db.user("USER0003").failedAttempts()).isZero();
    }

    @Test
    @SpecItem({"DEF-USRD-004", "Q-USRD-002"})
    void s26_theLastActiveAdministratorStays() {
        db.demoSeed();
        ApiClient admin1 = admin();
        for (String id : List.of("ADMIN003", "ADMIN004", "ADMIN005")) {
            assertThat(delete(admin1, id).status()).isEqualTo(200);
        }
        ApiClient admin2 = signedOn("ADMIN002");
        assertThat(delete(admin2, "ADMIN001").status()).isEqualTo(200);
        assertProblem(delete(admin2, "ADMIN002"), 409, "CANNOT_DELETE_SELF", "You cannot delete your own User ID...",
                "userId");
        // ADMIN001's access token outlives its row by up to five minutes; the count still refuses.
        assertProblem(delete(admin1, "ADMIN002"), 409, "LAST_ADMIN", "At least one administrator must remain...",
                "userId");
        assertThat(db.adminRow("ADMIN002").deletedAt()).isNull();
        assertThat(delete(admin2, "USER0001").status()).isEqualTo(200);
    }

    @Test
    @SpecItem({"DEF-USRD-004"})
    void s27_concurrentDeletesCannotRemoveTheLastTwoAdministrators() throws Exception {
        db.user("ADMIN001", "A", "ONE", 'A', IdentityFixtures.DEMO_PASSWORD, false);
        db.user("ADMIN002", "A", "TWO", 'A', IdentityFixtures.DEMO_PASSWORD, false);
        ApiClient admin1 = signedOn("ADMIN001");
        ApiClient admin2 = signedOn("ADMIN002");
        String tag1 = etag(admin1, "ADMIN001");
        String tag2 = etag(admin1, "ADMIN002");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> a = pool.submit(() -> {
                go.await();
                return admin1.delete("/api/users/ADMIN002", tag2);
            });
            Future<Response> b = pool.submit(() -> {
                go.await();
                return admin2.delete("/api/users/ADMIN001", tag1);
            });
            go.countDown();
            List<Response> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(results).filteredOn(r -> r.status() == 409).extracting(r -> r.text("code"))
                    .containsExactly("LAST_ADMIN");
        } finally {
            pool.shutdownNow();
        }
        long active = List.of("ADMIN001", "ADMIN002").stream().filter(id -> db.adminRow(id).deletedAt() == null)
                .count();
        assertThat(active).isEqualTo(1);
    }

    @Test
    @SpecItem({"DEF-USRD-001", "DEF-USRU-007"})
    void s28_aStaleVersionIsRefused() {
        db.demoSeed();
        ApiClient c = admin();
        String seen = etag(c, "USER0005");
        assertThat(c.put("/api/users/USER0005", change("LEE", "Changed", "U", null), seen).status()).isEqualTo(200);
        Response stale = c.delete("/api/users/USER0005", seen);
        assertProblem(stale, 409, "RECORD_CHANGED", "Record changed by some one else. Please review", null);
        assertThat(stale.body().path("current").path("lastName").asString()).isEqualTo("Changed");
        assertThat(db.adminRow("USER0005").deletedAt()).isNull();
        assertProblem(c.delete("/api/users/USER0005", null), 428, "PRECONDITION_REQUIRED",
                "If-Match header is required", null);
    }

    @Test
    @SpecItem({"ERR-USRD-001", "ERR-USRD-002", "DEF-USRD-003"})
    void s29_databaseFailures() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        doThrow(new DataAccessResourceFailureException("down")).when(userRepository)
                .markDeleted(anyString(), any(), anyString(), any());
        assertProblem(c.delete("/api/users/USER0001", etag), 500, "USER_DELETE_FAILED", "Unable to Update User...",
                "userId");
        assertThat(db.adminRow("USER0001").deletedAt()).isNull();
        assertThat(db.opsAudit()).isEmpty();

        doThrow(new DataAccessResourceFailureException("down")).when(userRepository).fetchForUpdate(anyString());
        assertProblem(c.delete("/api/users/USER0001", etag), 500, "USER_LOOKUP_FAILED", "Unable to lookup User...",
                "userId");
    }
}
