package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.OpsAuditRow;
import org.carddemo.identity.IdentityFixtures.SessionRow;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** USRU (COUSR02C): fetch and save, contract scenarios S16 to S23 and S29. */
class UserUpdateTest extends UserAdminTestBase {

    private static Response save(ApiClient c, String userId, String etag, String body) {
        return c.put("/api/users/" + userId, body, etag);
    }

    @Test
    @SpecItem({"BR-USRU-001", "BR-USRU-002", "VAL-USRU-001", "DEF-USRU-005"})
    void s16_fetchReturnsTheUserWithItsVersionAndNoPassword() {
        db.demoSeed();
        Response r = admin().get("/api/users/{id}", "user0001");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("userId")).isEqualTo("USER0001");
        assertThat(r.text("firstName")).isEqualTo("LAWRENCE");
        assertThat(r.text("lastName")).isEqualTo("THOMAS");
        assertThat(r.text("userType")).isEqualTo("U");
        assertThat(r.text("version")).matches("[0-9a-f]{16}");
        assertThat(r.header("ETag")).isEqualTo('"' + r.text("version") + '"');
        assertThat(r.body().has("password")).isFalse();
        assertThat(r.body().has("passwordHash")).isFalse();
    }

    @Test
    @SpecItem({"VAL-USRU-003", "VAL-USRU-001"})
    void s17_unknownOrBlankIds() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(c.get("/api/users/NOBODY"), 404, "USER_ID_NOT_FOUND", "User ID NOT found...", "userId");
        assertProblem(c.get("/api/users/{id}", " "), 400, "USER_ID_EMPTY", "User ID can NOT be empty...", "userId");
    }

    @Test
    @SpecItem({"BR-USRU-004"})
    void s18_savingTheFetchedValuesIsNoChange() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        assertProblem(save(c, "USER0001", etag, change("LAWRENCE", "THOMAS  ", "U", null)), 400, "NO_CHANGE",
                "Please modify to update ...", "firstName");
        assertProblem(save(c, "USER0001", etag, change("LAWRENCE", "THOMAS", "U", "   ")), 400, "NO_CHANGE",
                "Please modify to update ...", "firstName");
        assertThat(db.opsAudit()).isEmpty();
        assertThat(etag(c, "USER0001")).isEqualTo(etag);
    }

    @Test
    void s37_signOnsLeaveTheFetchedVersionAndAPasswordChangeDoesNot() {
        switches.all(FidelityMode.FIXED);
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        ApiClient user = client();
        assertThat(user.signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        assertThat(db.user("USER0001").failedAttempts()).isEqualTo(1);
        assertThat(user.signOn("USER0001", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(200);
        assertThat(db.user("USER0001").failedAttempts()).isZero();
        assertThat(db.user("USER0001").lastSignOnAt()).isNotNull();

        Response saved = save(c, "USER0001", etag, change("LAWRENCE", "SIGNEDON", "U", null));
        assertThat(saved.status()).as("%s", saved.body()).isEqualTo(200);
        String kept = saved.header("ETag");
        assertThat(kept).isNotEqualTo(etag).isEqualTo(etag(c, "USER0001"));

        // The password is part of the record the update page saves, so its change is another writer.
        Response changed = user.changePassword("USER0001", IdentityFixtures.DEMO_PASSWORD, "Correct horse 1");
        assertThat(changed.status()).as("%s", changed.body()).isEqualTo(200);
        Response stale = save(c, "USER0001", kept, change("LAWRENCE", "AFTER", "U", null));
        assertProblem(stale, 409, "RECORD_CHANGED", "Record changed by some one else. Please review", null);
        assertThat(stale.body().path("current").path("lastName").asString()).isEqualTo("SIGNEDON");
    }

    @Test
    void signOnsBetweenTheFetchAndTheSaveOrDeleteDoNotMakeThemStale() {
        switches.all(FidelityMode.FIXED);
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        String other = etag(c, "USER0002");
        assertThat(client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(200);
        assertThat(client().signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        assertThat(client().signOn("USER0002", IdentityFixtures.DEMO_PASSWORD).status()).isEqualTo(200);
        assertThat(db.user("USER0001").failedAttempts()).isEqualTo(1);
        assertThat(etag(c, "USER0001")).isEqualTo(etag);

        Response saved = save(c, "USER0001", etag, change("LAWRENCE", "SIGNEDON", "U", null));
        assertThat(saved.status()).as("%s", saved.body()).isEqualTo(200);
        assertThat(db.user("USER0001").failedAttempts()).as("a save without a password keeps the count")
                .isEqualTo(1);
        Response deleted = c.delete("/api/users/USER0002", other);
        assertThat(deleted.status()).as("%s", deleted.body()).isEqualTo(200);
    }

    @Test
    void aSetPasswordClearsTheLockout() {
        switches.all(FidelityMode.FIXED);
        db.demoSeed();
        for (int i = 0; i < 5; i++) {
            assertThat(client().signOn("USER0003", "WRONGPWD").status()).isEqualTo(401);
        }
        assertThat(db.user("USER0003").lockedUntil()).isNotNull();
        ApiClient c = admin();
        Response r = save(c, "USER0003", etag(c, "USER0003"), change("LAURITZ", "ALME", "U", "Newpass1"));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(db.user("USER0003").lockedUntil()).isNull();
        assertThat(db.user("USER0003").failedAttempts()).isZero();
        assertThat(client().signOn("USER0003", "Newpass1").status()).isEqualTo(200);
    }

    @Test
    @SpecItem({"BR-USRU-003", "Q-USRA-002"})
    void s19_aChangedLastNameIsSavedAndAudited() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        Response r = save(c, "USER0001", etag, change("LAWRENCE", "Thomas-Smith", "U", null));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("message")).isEqualTo("User USER0001 has been updated ...");
        assertThat(r.text("severity")).isEqualTo("success");
        String version = r.body().path("user").path("version").asString();
        assertThat(r.header("ETag")).isEqualTo('"' + version + '"').isNotEqualTo(etag);
        assertThat(r.body().path("user").path("lastName").asString()).isEqualTo("Thomas-Smith");
        assertThat(db.adminRow("USER0001").lastName()).isEqualTo("Thomas-Smith");
        assertThat(db.adminRow("USER0001").updatedBy()).isEqualTo("ADMIN001");
        assertThat(db.opsAudit()).containsExactly(
                new OpsAuditRow("identity-service", "ADMIN001", "UPDATE", "USER", "USER0001", "lastName"));
    }

    @Test
    @SpecItem({"DEF-USRU-007"})
    void s20_aStaleVersionIsRefusedWithTheCurrentValues() {
        db.demoSeed();
        ApiClient first = admin();
        ApiClient second = signedOn("ADMIN002");
        String seen = etag(first, "USER0002");
        assertThat(etag(second, "USER0002")).isEqualTo(seen);
        Response saved = save(first, "USER0002", seen, change("AJITH", "First", "U", null));
        assertThat(saved.status()).isEqualTo(200);

        Response stale = save(second, "USER0002", seen, change("AJITH", "Second", "U", null));
        assertProblem(stale, 409, "RECORD_CHANGED", "Record changed by some one else. Please review", null);
        assertThat(stale.body().path("current").path("lastName").asString()).isEqualTo("First");
        assertThat(stale.header("ETag")).isEqualTo(saved.header("ETag"));
        assertThat(db.adminRow("USER0002").lastName()).isEqualTo("First");
    }

    @Test
    @SpecItem({"DEF-USRU-007"})
    void s21_ifMatchIsRequiredAndStrong() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(save(c, "USER0001", null, change("", "", "", null)), 428, "PRECONDITION_REQUIRED",
                "If-Match header is required", null);
        assertProblem(save(c, "USER0001", "*", change("A", "B", "U", null)), 400, "INVALID_IF_MATCH",
                "If-Match header is not a version of this record", null);
        assertProblem(save(c, "NOBODY", "\"0000000000000000\"", change("A", "B", "U", null)), 404,
                "USER_ID_NOT_FOUND", "User ID NOT found...", "userId");
    }

    @Test
    @SpecItem({"DEF-USRU-005", "DEF-USRU-011", "DEF-SGN-011"})
    void s22_aSetPasswordEndsTheUsersSessions() {
        db.demoSeed();
        ApiClient user = signedOn("USER0003");
        ApiClient c = admin();
        Response r = save(c, "USER0003", etag(c, "USER0003"), change("LAURITZ", "ALME", "U", "newpass1"));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(db.sessions()).filteredOn(s -> s.userId().equals("USER0003"))
                .extracting(SessionRow::endReason).containsOnly("PASSWORD_CHANGE");
        assertThat(user.refresh().status()).isEqualTo(401);
        assertThat(db.opsAudit()).extracting(OpsAuditRow::fieldNames).containsExactly("password");
        assertThat(db.opsAudit().toString()).doesNotContainIgnoringCase("newpass1");
        assertThat(db.adminRow("USER0003").mustChange()).isFalse();
        assertThat(client().signOn("USER0003", "NEWPASS1").status()).isEqualTo(200);
        assertThat(client().signOn("USER0003", "PASSWORD").status()).isEqualTo(401);
    }

    @Test
    @SpecItem({"DEF-USRU-011"})
    void s23_theCurrentPasswordIsNotANewOne() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(save(c, "USER0001", etag(c, "USER0001"), change("LAWRENCE", "THOMAS", "U", "password")), 400,
                "NEW_PASSWORD_SAME", "New Password must differ from the current one ...", "password");
    }

    @Test
    @SpecItem({"DEF-USRU-004"})
    void aTypeChangeEndsTheSessionsAndALegacyTypeMustBeReplaced() {
        db.demoSeed();
        db.user("LEGACY01", "OLD", "TYPE", 'X', "PASSWORD", false);
        ApiClient user = signedOn("USER0001");
        ApiClient c = admin();
        Response r = save(c, "USER0001", etag(c, "USER0001"), change("LAWRENCE", "THOMAS", "A", null));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(db.sessions()).filteredOn(s -> s.userId().equals("USER0001"))
                .extracting(SessionRow::endReason).containsOnly("USER_REFUSED");
        assertThat(user.refresh().status()).isEqualTo(401);
        assertThat(db.opsAudit()).extracting(OpsAuditRow::fieldNames).containsExactly("userType");

        Response legacy = c.get("/api/users/LEGACY01");
        assertThat(legacy.text("userType")).isEqualTo("X");
        assertProblem(save(c, "LEGACY01", legacy.header("ETag"), change("OLD", "TYPE", "X", null)), 400,
                "USER_TYPE_INVALID", "User Type must be A or U...", "userType");
    }

    @Test
    @SpecItem({"ERR-USRU-001", "ERR-USRU-002"})
    void s29_databaseFailures() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        doThrow(new DataAccessResourceFailureException("down")).when(userRepository).fetch(anyString());
        assertProblem(c.get("/api/users/USER0001"), 500, "USER_LOOKUP_FAILED", "Unable to lookup User...", "userId");

        doThrow(new DataAccessResourceFailureException("down")).when(userRepository).fetchForUpdate(anyString());
        assertProblem(save(c, "USER0001", etag, change("L", "T", "U", null)), 500, "USER_LOOKUP_FAILED",
                "Unable to lookup User...", "firstName");
    }

    @Test
    @SpecItem({"ERR-USRU-002"})
    void s29_aFailedWriteGivesTheUpdateTextAndWritesNothing() {
        db.demoSeed();
        ApiClient c = admin();
        String etag = etag(c, "USER0001");
        doThrow(new DataAccessResourceFailureException("down")).when(userRepository)
                .update(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertProblem(save(c, "USER0001", etag, change("L", "T", "U", null)), 500, "USER_UPDATE_FAILED",
                "Unable to Update User...", "firstName");
        assertThat(db.adminRow("USER0001").firstName()).isEqualTo("LAWRENCE");
        assertThat(db.opsAudit()).isEmpty();
    }
}
