package org.carddemo.identity.signon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.carddemo.identity.RuleVectors;
import org.carddemo.identity.audit.AuditLog;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.session.SessionService;
import org.carddemo.identity.user.AppUser;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The sign-on and password change forms of the shared rule table against {@link SignOnService},
 * without a database. Every user exists and every current password is right, so a vector the form
 * passes reaches the transaction that starts the session, and one it refuses gets the form's
 * first problem (field, code and text).
 *
 * <p>The forms leave out the user ID length rule (rule 3, {@code INVALID_INPUT_USER_ID}); only
 * the server applies it. A vector with a user ID of more than 8 characters and a password is a
 * server-only case: the service refuses it with rule 3, whatever the form says.
 */
class SignOnFormRulesParityTest {

    private static final RuntimeException TRANSACTION_REACHED = new IllegalStateException("transaction reached");
    private static final String RULE_3 = "userId INVALID_INPUT Please enter User ID ... (400)";

    private enum Call { SIGN_ON, CHANGE }

    @Test
    void signOnFollowsTheFormInBothModes() {
        for (FidelityMode mode : FidelityMode.values()) {
            assertThat(differences("signOn", Call.SIGN_ON, mode, null)).as(mode.name()).isEmpty();
        }
    }

    /** The forced change before the first sign-on is a fixed-mode flow; the full form is fixed only. */
    @Test
    void forcedChangeFollowsTheFixedForm() {
        assertThat(differences("passwordChange", Call.CHANGE, FidelityMode.FIXED, null)).isEmpty();
    }

    /**
     * The blank-only form is what the page runs while it does not know the mode. What it refuses,
     * the service refuses the same way in either mode; what it passes, the service may still refuse
     * with a new-password rule or rule 4, which the form does not hold.
     */
    @Test
    void anyModeFormIsASafeSubsetInBothModes() {
        Set<String> serverRules = Set.of("NEW_PASSWORD_LENGTH", "NEW_PASSWORD_SAME", "NEW_PASSWORD_IS_USER_ID",
                "INVALID_INPUT");
        for (FidelityMode mode : FidelityMode.values()) {
            assertThat(differences("passwordChangeAnyMode", Call.CHANGE, mode, serverRules)).as(mode.name()).isEmpty();
        }
    }

    /** Rule 2 comes before rule 3, so the blank password is reported even with a 9-character ID. */
    @Test
    void blankPasswordBeforeUserIdLength() {
        Map<String, String> in = Map.of("userId", "USER00012", "password", " ");
        FieldProblem first = RuleTable.standard().form("signOn").validate(in, LocalDate.of(2026, 11, 11))
                .first().orElseThrow();
        String expected = "password PASSWORD_REQUIRED Please enter Password ... (400)";
        assertThat(outcome(first)).isEqualTo(expected);
        for (FidelityMode mode : FidelityMode.values()) {
            assertThat(call(service(mode), Call.SIGN_ON, in)).isEqualTo(expected);
        }
    }

    @Test
    void userIdLengthIsServerOnly() {
        Map<String, String> in = Map.of("userId", "USER00012", "password", "Password1");
        assertThat(RuleTable.standard().form("signOn").validate(in, LocalDate.of(2026, 11, 11)).valid()).isTrue();
        for (FidelityMode mode : FidelityMode.values()) {
            assertThat(call(service(mode), Call.SIGN_ON, in)).isEqualTo(RULE_3);
        }
    }

    private static List<String> differences(String form, Call call, FidelityMode mode, Set<String> serverRules) {
        SignOnService service = service(mode);
        List<String> differences = new ArrayList<>();
        int cases = 0;
        int passing = 0;
        for (JsonNode c : RuleVectors.shippedCases(form)) {
            cases++;
            Map<String, String> in = RuleVectors.input(c);
            List<FieldProblem> problems = RuleTable.standard().form(form)
                    .validate(in, LocalDate.parse(c.get("today").asString())).problems();
            String actual = call(service, call, in);
            String password = call == Call.SIGN_ON ? in.get("password") : in.get("currentPassword");
            boolean serverOnly = !LegacyText.isBlank(in.get("userId")) && !LegacyText.isBlank(password)
                    && LegacyText.rtrim(in.get("userId")).codePointCount(0, LegacyText.rtrim(in.get("userId")).length()) > 8;
            boolean ok;
            String expected;
            if (serverOnly) {
                expected = "rule 3 (server only)";
                ok = actual.equals(RULE_3);
            } else if (!problems.isEmpty()) {
                expected = outcome(problems.get(0));
                ok = actual.equals(expected);
            } else {
                passing++;
                expected = serverRules == null ? "transaction" : "transaction or one of " + serverRules;
                ok = actual.equals("transaction")
                        || serverRules != null && serverRules.stream().anyMatch(r -> actual.contains(" " + r + " "));
            }
            if (!ok) {
                differences.add(c.get("name").asString() + ": expected " + expected + ", service " + actual);
            }
        }
        assertThat(cases).as(form + " vectors").isGreaterThan(5);
        assertThat(passing).as(form + " vectors that pass").isPositive();
        return differences;
    }

    private static String call(SignOnService service, Call call, Map<String, String> in) {
        try {
            if (call == Call.SIGN_ON) {
                service.signOn(in.get("userId"), in.get("password"), "203.0.113.7");
            } else {
                service.changePassword(in.get("userId"), in.get("currentPassword"), in.get("newPassword"),
                        Optional.empty(), "203.0.113.7");
            }
            return "no refusal and no transaction";
        } catch (AuthFailure e) {
            return e.field() + " " + e.code() + " " + e.getMessage() + " (" + e.status().value() + ")";
        } catch (IllegalStateException e) {
            if (e != TRANSACTION_REACHED) {
                throw e;
            }
            return "transaction";
        }
    }

    private static String outcome(FieldProblem p) {
        return p.field() + " " + p.code() + " " + p.message() + " (400)";
    }

    /** Every switch in {@code mode}: every user exists, is not locked and has the typed password. */
    private static SignOnService service(FidelityMode mode) {
        FidelitySwitches switches = FidelitySwitches.allLegacy(IdentitySwitches.ALL);
        for (FidelitySwitch s : IdentitySwitches.ALL) {
            switches = switches.with(s, mode);
        }
        UserRepository users = mock(UserRepository.class);
        when(users.find(anyString())).thenAnswer(inv -> Optional.of(new AppUser(inv.getArgument(0), "Ada", "Lovelace",
                "{argon2}not-checked", AppUser.TYPE_USER, false, 0, null)));
        PasswordHasher hasher = mock(PasswordHasher.class);
        when(hasher.matches(anyString(), anyString())).thenReturn(true);
        when(hasher.hash(anyString())).thenReturn("{argon2}not-checked");
        SafeTransactionTemplate tx = mock(SafeTransactionTemplate.class);
        when(tx.execute(any())).thenThrow(TRANSACTION_REACHED);
        return new SignOnService(users, hasher, mock(SessionService.class), mock(AuditLog.class),
                mock(AttemptThrottle.class), new IdentityFidelity(switches), null,
                Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC), tx);
    }
}
