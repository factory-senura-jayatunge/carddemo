package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AuditRow;
import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;

/**
 * A change committed between the password check and the session: the sign-on and the password
 * change read the user and its sign-on state again under an update lock, in the transaction that
 * starts the session, and apply the rules to what they read. The hook commits the change from
 * another connection just before that read.
 */
class SignOnRecheckTest extends IdentityTestBase {

    private static final String NEUTRAL = "Wrong User ID or Password. Try again ...";

    private void beforeTheLockedRead(Runnable change) {
        doAnswer(inv -> {
            change.run();
            return inv.callRealMethod();
        }).when(userRepository).findLocked(anyString());
    }

    private void assertRefused(Response r, int status, String code, String reason) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(status);
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.cookie("cd_rt")).isNull();
        assertThat(db.sessions()).isEmpty();
        AuditRow last = db.audit().getLast();
        assertThat(last.eventType()).isEqualTo("SIGN_ON_FAILED");
        assertThat(last.subject()).isEqualTo("USER0001");
        assertThat(last.detail()).isEqualTo(reason);
        assertThat(db.user("USER0001").failedAttempts()).as("nothing counted").isZero();
    }

    @Test
    void aTypeTheFixedModeRefusesSetMeanwhileRefuses() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        beforeTheLockedRead(() -> db.setUser("USER0001", "user_type = N'X'"));
        assertRefused(client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD), 403, "USER_TYPE_NOT_ALLOWED",
                "USER_TYPE_NOT_ALLOWED");
    }

    @Test
    void theRoleComesFromTheTypeReadUnderTheLock() {
        db.demoUsers();
        beforeTheLockedRead(() -> db.setUser("USER0001", "user_type = N'A'"));
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("userType")).isEqualTo("A");
        assertThat(r.text("role")).isEqualTo("ADMIN");
        assertThat(r.text("home")).isEqualTo("/admin");
    }

    @Test
    void aLockSetMeanwhileRefusesWithTheNeutralText() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        LocalDateTime until = LocalDateTime.ofInstant(START, ZoneOffset.UTC).plusMinutes(15);
        beforeTheLockedRead(() -> db.signOnState("USER0001", 0, until, null));
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertRefused(r, 401, "SIGN_ON_FAILED", "USER_LOCKED");
        assertThat(r.text("message")).isEqualTo(NEUTRAL);
        assertThat(db.user("USER0001").lockedUntil()).isEqualTo(until);
    }

    @Test
    void aForcedChangeSetMeanwhileRefusesInFixedMode() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        beforeTheLockedRead(() -> db.setUser("USER0001", "must_change_password = 1"));
        assertRefused(client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD), 403, "PASSWORD_CHANGE_REQUIRED",
                "PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    void aDeleteMeanwhileMakesTheUserUnknown() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        beforeTheLockedRead(() -> db.setUser("USER0001", "deleted_at = SYSUTCDATETIME(), deleted_by = N'ADMIN001'"));
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertRefused(r, 401, "SIGN_ON_FAILED", "USER_NOT_FOUND");
        assertThat(r.text("message")).isEqualTo(NEUTRAL);
    }

    @Test
    void aPasswordChangedMeanwhileIsAWrongPassword() {
        db.demoUsers();
        beforeTheLockedRead(() -> db.changeHash("USER0001", "OTHERPW1"));
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertRefused(r, 401, "WRONG_PASSWORD", "WRONG_PASSWORD");
        assertThat(r.text("field")).isEqualTo("password");
    }

    @Test
    void thePasswordChangeRechecksToo() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        String before = db.user("USER0001").passwordHash();
        LocalDateTime until = LocalDateTime.ofInstant(START, ZoneOffset.UTC).plusMinutes(15);
        beforeTheLockedRead(() -> db.signOnState("USER0001", 0, until, null));
        Response r = client().changePassword("USER0001", IdentityFixtures.DEMO_PASSWORD, "Changed#2026");
        assertRefused(r, 401, "SIGN_ON_FAILED", "USER_LOCKED");
        assertThat(r.text("field")).isEqualTo("currentPassword");
        assertThat(db.user("USER0001").passwordHash()).as("not changed").isEqualTo(before);
        assertThat(db.audit()).noneMatch(a -> a.eventType().equals("PASSWORD_CHANGED"));
    }

    @Test
    void withoutAChangeTheSignOnIsAsBefore() {
        switches.all(FidelityMode.FIXED);
        db.demoUsers();
        beforeTheLockedRead(() -> { });
        Response r = client().signOn("USER0001", IdentityFixtures.DEMO_PASSWORD);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.text("role")).isEqualTo("USER");
        assertThat(db.sessions()).hasSize(1);
    }
}
