package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AuditRow;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SGN S14, S21 and the new-password rules (D-SEC-07). */
class PasswordChangeTest extends IdentityTestBase {

    private static void assertProblem(Response r, int status, String code, String message, String field) {
        assertThat(r.status()).as("status of %s", r.body()).isEqualTo(status);
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.text("message")).isEqualTo(message);
        assertThat(r.text("field")).isEqualTo(field);
    }

    @Test
    @SpecItem({"DEF-SGN-011"})
    void s14_fixedForcesTheChangeThenLandsOnHome() {
        switches.all(FidelityMode.FIXED);
        db.user("MUSTCHG1", "MUST", "CHANGE", 'U', "PASSWORD", true);
        ApiClient c = client();

        Response refused = c.signOn("MUSTCHG1", "PASSWORD");
        assertProblem(refused, 403, "PASSWORD_CHANGE_REQUIRED", "Password change required. Enter a new Password ...",
                "newPassword");
        assertThat(refused.text("severity")).isEqualTo("info");
        assertThat(refused.cookie("cd_at")).isNull();
        assertThat(db.sessions()).isEmpty();

        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "  "), 400, "NEW_PASSWORD_REQUIRED",
                "Please enter new Password ...", "newPassword");
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "Short1!"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 8 to 64 characters ...", "newPassword");
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "x".repeat(65)), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 8 to 64 characters ...", "newPassword");
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "PASSWORD"), 400, "NEW_PASSWORD_SAME",
                "New Password must differ from the current one ...", "newPassword");
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "mustchg1"), 400, "NEW_PASSWORD_IS_USER_ID",
                "New Password must not be the User ID ...", "newPassword");
        assertProblem(c.changePassword("MUSTCHG1", "WRONGPWD", "Correct horse 1"), 401, "SIGN_ON_FAILED",
                "Wrong User ID or Password. Try again ...", "currentPassword");

        Response changed = c.changePassword("mustchg1", "PASSWORD", "Correct horse 1");
        assertThat(changed.status()).as("%s", changed.body()).isEqualTo(200);
        assertThat(changed.text("userId")).isEqualTo("MUSTCHG1");
        assertThat(changed.text("home")).isEqualTo("/");
        assertThat(changed.cookie("cd_at")).isNotNull();
        assertThat(changed.cookie("XSRF-TOKEN")).isNotNull();
        assertThat(c.me().status()).isEqualTo(200);
        assertThat(db.user("MUSTCHG1").mustChange()).isFalse();
        assertThat(db.audit()).extracting(AuditRow::eventType).endsWith("PASSWORD_CHANGED", "SIGN_ON_OK");

        assertThat(client().signOn("MUSTCHG1", "Correct horse 1").status()).isEqualTo(200);
        assertThat(client().signOn("MUSTCHG1", "CORRECT HORSE 1").status()).isEqualTo(401);
        assertThat(client().signOn("MUSTCHG1", "PASSWORD").status()).isEqualTo(401);
    }

    @Test
    @SpecItem({"DEF-SGN-011"})
    void s14_legacyIgnoresTheFlag() {
        db.user("MUSTCHG1", "MUST", "CHANGE", 'U', "PASSWORD", true);
        Response r = client().signOn("MUSTCHG1", "password");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("home")).isEqualTo("/");
    }

    @Test
    @SpecItem({"DEF-SGN-011", "BR-SGN-003"})
    void legacyRulesStoreTheUpperCasedPassword() {
        db.demoUsers();
        ApiClient c = client();
        assertProblem(c.changePassword("USER0001", "PASSWORD", "TOOLONG99"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 1 to 8 characters ...", "newPassword");
        assertProblem(c.changePassword("USER0001", "PASSWORD", "password"), 400, "NEW_PASSWORD_SAME",
                "New Password must differ from the current one ...", "newPassword");
        assertProblem(c.changePassword("USER0001", "WRONGPWD", "newpass1"), 401, "WRONG_PASSWORD",
                "Wrong Password. Try again ...", "currentPassword");
        assertProblem(c.changePassword(null, "PASSWORD", "newpass1"), 400, "USER_ID_REQUIRED",
                "Please enter User ID ...", "userId");
        // Legacy accepts a user-ID-like password and a short one.
        assertThat(c.changePassword("USER0001", "PASSWORD", "user0001").status()).isEqualTo(200);
        assertThat(client().signOn("USER0001", "USER0001").status()).isEqualTo(200);
        assertThat(client().changePassword("USER0001", "user0001", "x").status()).isEqualTo(200);
        assertThat(client().signOn("USER0001", "X").status()).isEqualTo(200);

        // The stored value signs on in fixed mode too only when typed upper case.
        switches.all(FidelityMode.FIXED);
        assertThat(client().signOn("USER0001", "x").status()).isEqualTo(401);
        assertThat(client().signOn("USER0001", "X").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-SGN-011"})
    void signedOnChangeUsesTheSessionUserAndEndsTheOldSession(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
        String oldSession = db.sessions().getFirst().sessionId();

        assertProblem(c.changePassword("USER0002", "PASSWORD", "Another pass 9"), 400, "USER_ID_MISMATCH",
                "User ID does not match the signed-on user ...", "userId");

        Response changed = c.changePassword(null, "PASSWORD", "NEWPASS9");
        assertThat(changed.status()).as("%s", changed.body()).isEqualTo(200);
        assertThat(changed.text("userId")).isEqualTo("USER0001");
        assertThat(db.sessions()).filteredOn(s -> s.sessionId().equals(oldSession))
                .extracting(IdentityFixtures.SessionRow::endReason).containsExactly("PASSWORD_CHANGE");
        assertThat(db.sessions()).filteredOn(s -> s.endReason() == null).hasSize(1);
        assertThat(client().signOn("USER0001", "NEWPASS9").status()).isEqualTo(200);
    }

    @Test
    @SpecItem({"DEF-SGN-002", "DEF-SGN-011"})
    void wrongCurrentPasswordsCountTowardsTheLock() {
        switches.all(FidelityMode.FIXED);
        db.user("MUSTCHG1", "MUST", "CHANGE", 'U', "PASSWORD", true);
        ApiClient c = client();
        for (int i = 0; i < 5; i++) {
            c.changePassword("MUSTCHG1", "WRONGPWD", "Correct horse 1");
        }
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "Correct horse 1"), 401, "SIGN_ON_FAILED",
                "Wrong User ID or Password. Try again ...", "currentPassword");
        assertThat(db.user("MUSTCHG1").lockedUntil()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-SGN-002"})
    void s21_userIdMismatchReadsAndCountsNothing(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
        clearInvocations(userRepository);

        // Steps 1 to 3 come first.
        assertProblem(c.changePassword("USER0002", " ", "Another pass 9"), 400, "PASSWORD_REQUIRED",
                "Please enter Password ...", "currentPassword");
        assertProblem(c.changePassword("USER00021", "PASSWORD", "Another pass 9"), 400, "INVALID_INPUT",
                "Please enter User ID ...", "userId");
        for (int i = 0; i < 35; i++) {
            assertProblem(c.changePassword("user0002", "WRONGPWD", "Another pass 9"), 400, "USER_ID_MISMATCH",
                    "User ID does not match the signed-on user ...", "userId");
        }
        verify(userRepository, never()).find(anyString());
        assertThat(db.user("USER0002").failedAttempts()).isZero();
        assertThat(db.audit()).last()
                .isEqualTo(new AuditRow("SIGN_ON_FAILED", null, "USER0002", "USER_ID_MISMATCH", "127.0.0.1"));
        // Neither the lockout nor the rate limit moved: the user's own ID, in any case, still works.
        Response changed = c.changePassword("user0001", "PASSWORD", "NEWPASS9");
        assertThat(changed.status()).as("%s", changed.body()).isEqualTo(200);
    }}
