package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AuditRow;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** COSGN00C section 15 scenarios S2 to S13, S18 and S19, through the API; both modes where the table has two. */
class SignOnScenariosTest extends IdentityTestBase {

    private static final String NEUTRAL = "Wrong User ID or Password. Try again ...";
    private static final String TOO_MANY = "Too many sign-on attempts. Try again later ...";

    private static void assertProblem(Response r, int status, String code, String message, String field) {
        assertThat(r.status()).as("status of %s", r.body()).isEqualTo(status);
        assertThat(r.text("code")).isEqualTo(code);
        assertThat(r.text("message")).isEqualTo(message);
        assertThat(r.text("field")).isEqualTo(field);
        assertThat(r.body().has("field")).as("field is always present").isTrue();
        assertThat(r.raw().getContentType()).startsWith("application/problem+json");
    }

    private static void assertSignedOn(Response r, String userId, String type, String role, String home) {
        assertView(r, userId, type, role, home);
        assertThat(r.cookie("cd_at")).isNotNull();
        assertThat(r.cookie("cd_rt")).isNotNull();
        assertThat(r.cookie("XSRF-TOKEN")).isNotNull();
    }

    private static void assertView(Response r, String userId, String type, String role, String home) {
        assertThat(r.status()).as("status of %s", r.body()).isEqualTo(200);
        assertThat(r.text("userId")).isEqualTo(userId);
        assertThat(r.text("userType")).isEqualTo(type);
        assertThat(r.text("role")).isEqualTo(role);
        assertThat(r.text("home")).isEqualTo(home);
        assertThat(r.body().path("idleTimeoutSeconds").asLong()).isEqualTo(900);
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"VAL-SGN-001"})
    void s2_bothBlank(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        assertProblem(client().signOn("", ""), 400, "USER_ID_REQUIRED", "Please enter User ID ...", "userId");
        assertProblem(client().signOn("        ", "        "), 400, "USER_ID_REQUIRED", "Please enter User ID ...",
                "userId");
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"VAL-SGN-002"})
    void s3_passwordBlank(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        assertProblem(client().signOn("ADMIN001", ""), 400, "PASSWORD_REQUIRED", "Please enter Password ...",
                "password");
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"VAL-SGN-001", "VAL-SGN-002"})
    void s3b_overlongFieldsFromScripts(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        assertProblem(client().signOn("ADMIN0001", "PASSWORD"), 400, "INVALID_INPUT", "Please enter User ID ...",
                "userId");
        assertProblem(client().signOn("ADMIN001", "x".repeat(65)), 400, "INVALID_INPUT", "Please enter Password ...",
                "password");
        // Trailing spaces are the 3270 field padding, not part of the value.
        assertThat(client().signOn("ADMIN001  ", "PASSWORD   ").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-004", "BR-SGN-005", "FLOW-SGN-001"})
    void s4_adminSignsOnToAdminMenu(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        assertSignedOn(c.signOn("ADMIN001", "PASSWORD"), "ADMIN001", "A", "ADMIN", "/admin");
        Response me = c.me();
        assertView(me, "ADMIN001", "A", "ADMIN", "/admin");
        assertThat(me.setCookies()).as("an authenticated call keeps XSRF-TOKEN").isEmpty();
        assertThat(me.text("firstName")).isEqualTo("MARGARET");
        assertThat(me.text("lastName")).isEqualTo("GOLD");
        assertThat(db.user("ADMIN001").lastSignOnAt()).isEqualTo(LocalDateTime.ofInstant(START, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-001", "BR-SGN-003", "DEF-SGN-011"})
    void s5_lowerCaseIdAndPassword(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        Response r = client().signOn("user0001", "password");
        if (mode == FidelityMode.LEGACY) {
            assertSignedOn(r, "USER0001", "U", "USER", "/");
        } else {
            assertProblem(r, 401, "SIGN_ON_FAILED", NEUTRAL, "password");
        }
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-001"})
    void s5b_userIdsStayCaseInsensitive(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        assertSignedOn(client().signOn("user0001", "PASSWORD"), "USER0001", "U", "USER", "/");
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"VAL-SGN-003", "BR-SGN-002", "DEF-SGN-003"})
    void s6_unknownUser(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        Response r = client().signOn("NOUSER01", "PASSWORD");
        if (mode == FidelityMode.LEGACY) {
            assertProblem(r, 401, "USER_NOT_FOUND", "User not found. Try again ...", "userId");
        } else {
            assertProblem(r, 401, "SIGN_ON_FAILED", NEUTRAL, "password");
        }
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"VAL-SGN-004", "DEF-SGN-003"})
    void s7_wrongPassword(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        Response r = client().signOn("ADMIN001", "WRONGPWD");
        if (mode == FidelityMode.LEGACY) {
            assertProblem(r, 401, "WRONG_PASSWORD", "Wrong Password. Try again ...", "password");
        } else {
            assertProblem(r, 401, "SIGN_ON_FAILED", NEUTRAL, "password");
        }
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-005"})
    void s10_signOnDuringBatchWindow(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers().openBatchWindow();
        assertSignedOn(client().signOn("USER0001", "PASSWORD"), "USER0001", "U", "USER", "/");
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-003", "DEF-SGN-011"})
    void s11_caseSensitivePassword(FidelityMode mode) {
        switches.all(mode);
        db.user("TEST0001", "TEST", "USER", 'U', "Secret12", false);
        Response r = client().signOn("TEST0001", "Secret12");
        if (mode == FidelityMode.LEGACY) {
            assertProblem(r, 401, "WRONG_PASSWORD", "Wrong Password. Try again ...", "password");
        } else {
            assertSignedOn(r, "TEST0001", "U", "USER", "/");
        }
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-SGN-002"})
    void s12_lockoutAfterFiveFailures(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        for (int i = 0; i < 5; i++) {
            assertThat(c.signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        }
        Response right = c.signOn("USER0001", "PASSWORD");
        if (mode == FidelityMode.LEGACY) {
            assertSignedOn(right, "USER0001", "U", "USER", "/");
            assertThat(db.audit()).noneMatch(a -> a.eventType().equals("USER_LOCKED"));
            return;
        }
        assertProblem(right, 401, "SIGN_ON_FAILED", NEUTRAL, "password");
        IdentityFixtures.UserRow locked = db.user("USER0001");
        assertThat(locked.failedAttempts()).isZero();
        assertThat(locked.lockedUntil()).isEqualTo(LocalDateTime.ofInstant(START, ZoneOffset.UTC).plusMinutes(15));
        assertThat(db.audit()).filteredOn(a -> a.eventType().equals("USER_LOCKED"))
                .extracting(AuditRow::subject).containsExactly("USER0001");
        // A locked user gets the neutral message whatever the password, and the lock does not count.
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertProblem(c.signOn("USER0001", "PASSWORD"), 401, "SIGN_ON_FAILED", NEUTRAL, "password");
        assertThat(db.audit()).last().extracting(AuditRow::detail).isEqualTo("USER_LOCKED");
        clock.advance(Duration.ofSeconds(1));
        assertSignedOn(c.signOn("USER0001", "PASSWORD"), "USER0001", "U", "USER", "/");
        assertThat(db.user("USER0001").lockedUntil()).isNull();
    }

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-SGN-002"})
    void s12b_successResetsTheCount(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        for (int i = 0; i < 4; i++) {
            c.signOn("USER0001", "WRONGPWD");
        }
        assertThat(db.user("USER0001").failedAttempts()).isEqualTo(mode == FidelityMode.FIXED ? 4 : 0);
        assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
        assertThat(db.user("USER0001").failedAttempts()).isZero();
        assertThat(c.signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-SGN-002"})
    void s19_fixedRateLimitsFailedAttemptsPerClient(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        for (int i = 0; i < 4; i++) {
            assertThat(c.signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        }
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 26; i++) {
            assertThat(c.signOn(String.format("NOUSR%03d", i), "PASSWORD").status()).isEqualTo(401);
        }
        clock.advance(Duration.ofSeconds(20));
        clearInvocations(userRepository);

        Response limited = c.signOn("USER0001", "WRONGPWD");
        assertProblem(limited, 429, "TOO_MANY_ATTEMPTS", TOO_MANY, "userId");
        assertThat(limited.raw().getHeader("Retry-After")).isEqualTo("30");
        assertProblem(c.signOn("USER0001", "PASSWORD"), 429, "TOO_MANY_ATTEMPTS", TOO_MANY, "userId");
        // Nothing read and no counter changed: the user's count stays at 4, no lock.
        verify(userRepository, never()).find(anyString());
        assertThat(db.user("USER0001").failedAttempts()).isEqualTo(4);
        assertThat(db.user("USER0001").lockedUntil()).isNull();
        assertThat(db.audit()).last()
                .isEqualTo(new AuditRow("SIGN_ON_FAILED", null, "USER0001", "TOO_MANY_ATTEMPTS", "127.0.0.1"));
        // Another address is not limited.
        assertThat(client().from("10.0.0.2").signOn("USER0002", "PASSWORD").status()).isEqualTo(200);

        // The window slides from the oldest failure; the refused requests did not extend it.
        clock.advance(Duration.ofSeconds(29));
        assertProblem(c.signOn("USER0001", "PASSWORD"), 429, "TOO_MANY_ATTEMPTS", TOO_MANY, "userId");
        clock.advance(Duration.ofSeconds(1));
        assertSignedOn(c.signOn("USER0001", "PASSWORD"), "USER0001", "U", "USER", "/");
        assertThat(db.user("USER0001").failedAttempts()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-SGN-002"})
    void s19_fixedRateLimitCoversThePasswordChange(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        db.user("MUSTCHG1", "MUST", "CHANGE", 'U', "PASSWORD", true);
        ApiClient c = client();
        for (int i = 0; i < 30; i++) {
            assertThat(c.changePassword(String.format("NOUSR%03d", i), "PASSWORD", "Correct horse 1").status())
                    .isEqualTo(401);
        }
        assertProblem(c.changePassword("MUSTCHG1", "PASSWORD", "Correct horse 1"), 429, "TOO_MANY_ATTEMPTS",
                TOO_MANY, "userId");
        assertProblem(c.signOn("USER0001", "PASSWORD"), 429, "TOO_MANY_ATTEMPTS", TOO_MANY, "userId");
        assertThat(db.user("MUSTCHG1").mustChange()).isTrue();
        assertThat(db.sessions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-SGN-002"})
    void s19_fixedNeverLimitsSuccessesOrBlankFields(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        for (int i = 0; i < 40; i++) {
            assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
            assertThat(c.signOn("", "").status()).isEqualTo(400);
        }
        assertThat(c.signOn("USER0001", "PASSWORD").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-SGN-002"})
    void s19_legacyHasNoLimit(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        ApiClient c = client();
        for (int i = 0; i < 40; i++) {
            assertThat(c.signOn(String.format("NOUSR%03d", i), "PASSWORD").status()).isEqualTo(401);
            assertThat(c.signOn("USER0001", "WRONGPWD").status()).isEqualTo(401);
        }
        assertSignedOn(c.signOn("USER0001", "PASSWORD"), "USER0001", "U", "USER", "/");
        assertThat(db.audit()).noneMatch(a -> "TOO_MANY_ATTEMPTS".equals(a.detail()));
    }
    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"BR-SGN-004", "DEF-SGN-005"})
    void s13_unknownUserType(FidelityMode mode) {
        switches.all(mode);
        db.user("TYPEX001", "XAVIER", "EXAMPLE", 'X', "PASSWORD", false);
        Response r = client().signOn("TYPEX001", "PASSWORD");
        if (mode == FidelityMode.LEGACY) {
            assertSignedOn(r, "TYPEX001", "X", "USER", "/");
        } else {
            assertProblem(r, 403, "USER_TYPE_NOT_ALLOWED", "User not authorized. Contact your administrator ...",
                    "userId");
            assertThat(db.sessions()).isEmpty();
        }
    }

    @Test
    @SpecItem({"ERR-SGN-001"})
    void databaseFailureIsUnableToVerify() {
        db.demoUsers();
        doThrow(new DataAccessResourceFailureException("connection refused")).when(userRepository).find(anyString());
        assertProblem(client().signOn("ADMIN001", "PASSWORD"), 503, "UNABLE_TO_VERIFY", "Unable to verify the User ...",
                "userId");
        assertThat(db.audit()).extracting(AuditRow::detail).containsExactly("UNABLE_TO_VERIFY");
    }

    @Test
    void anUnexpectedFailureIsAnInternalErrorWithoutTheCause() {
        db.demoUsers();
        doThrow(new IllegalStateException("secret cause")).when(userRepository).find(anyString());
        Response r = client().signOn("ADMIN001", "PASSWORD");
        assertProblem(r, 500, "INTERNAL_ERROR", "An unexpected error occurred. Please try again later ...", null);
        assertThat(r.body().toString()).doesNotContain("secret cause");
        String correlationId = r.raw().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(r.text("correlationId")).isEqualTo(correlationId);
    }

    @ParameterizedTest    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-SGN-002", "DEF-SGN-003"})
    void s18_everyOutcomeIsAuditedWithoutThePassword(FidelityMode mode) {
        switches.all(mode);
        db.demoUsers();
        db.user("TYPEX001", "XAVIER", "EXAMPLE", 'X', "PASSWORD", false);
        ApiClient c = client();
        c.signOn("", "TOPSECRT");
        c.signOn("ADMIN001", "");
        c.signOn("NOUSER01", "TOPSECRT");
        c.signOn("ADMIN001", "TOPSECRT");
        c.signOn("TYPEX001", "PASSWORD");
        c.signOn("ADMIN001", "PASSWORD");
        String typeX = mode == FidelityMode.LEGACY ? "SIGN_ON_OK" : "SIGN_ON_FAILED";
        String typeXDetail = mode == FidelityMode.LEGACY ? null : "USER_TYPE_NOT_ALLOWED";
        String typeXActor = mode == FidelityMode.LEGACY ? "TYPEX001" : null;
        assertThat(db.audit()).containsExactly(
                new AuditRow("SIGN_ON_FAILED", null, null, "USER_ID_REQUIRED", "127.0.0.1"),
                new AuditRow("SIGN_ON_FAILED", null, "ADMIN001", "PASSWORD_REQUIRED", "127.0.0.1"),
                new AuditRow("SIGN_ON_FAILED", null, "NOUSER01", "USER_NOT_FOUND", "127.0.0.1"),
                new AuditRow("SIGN_ON_FAILED", null, "ADMIN001", "WRONG_PASSWORD", "127.0.0.1"),
                new AuditRow(typeX, typeXActor, "TYPEX001", typeXDetail, "127.0.0.1"),
                new AuditRow("SIGN_ON_OK", "ADMIN001", "ADMIN001", null, "127.0.0.1"));
        assertThat(db.auditText()).doesNotContain("TOPSECRT").doesNotContain("|PASSWORD|");
    }
}
