package org.carddemo.identity.user;

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

import org.carddemo.identity.RuleVectors;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.session.SessionRepository;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The user add and update forms of the shared rule table against {@link UserAdminService}, without
 * a database: for every shipped vector of a form, the service refuses with the form's first
 * problem (field, code and text), and reaches its transaction exactly when the form passes.
 * {@code userAdd} and {@code userUpdate} run with DEF-SGN-011 fixed, the {@code Legacy} forms with
 * it legacy.
 */
class UserFormRulesParityTest {

    /** Where the service would start writing; nothing before it reads or writes a row. */
    private static final RuntimeException TRANSACTION_REACHED = new IllegalStateException("transaction reached");
    private static final String IF_MATCH = "\"0123456789abcdef\"";

    @Test
    void addFollowsTheFixedForm() {
        assertThat(differences("userAdd", FidelityMode.FIXED, false)).isEmpty();
    }

    @Test
    void addFollowsTheLegacyForm() {
        assertThat(differences("userAddLegacy", FidelityMode.LEGACY, false)).isEmpty();
    }

    @Test
    void updateFollowsTheFixedForm() {
        assertThat(differences("userUpdate", FidelityMode.FIXED, true)).isEmpty();
    }

    @Test
    void updateFollowsTheLegacyForm() {
        assertThat(differences("userUpdateLegacy", FidelityMode.LEGACY, true)).isEmpty();
    }

    private static List<String> differences(String form, FidelityMode passwordCase, boolean update) {
        UserAdminService service = service(passwordCase);
        List<String> differences = new ArrayList<>();
        int cases = 0;
        int passing = 0;
        for (JsonNode c : RuleVectors.shippedCases(form)) {
            cases++;
            Map<String, String> in = RuleVectors.input(c);
            List<FieldProblem> problems = RuleTable.standard().form(form)
                    .validate(in, LocalDate.parse(c.get("today").asString())).problems();
            String expected = problems.isEmpty() ? "transaction" : outcome(problems.get(0));
            if (problems.isEmpty()) {
                passing++;
            }
            String actual;
            try {
                if (update) {
                    service.update("ADMIN001", in.get("userId"), IF_MATCH, new UserAdminService.UserChange(
                            in.get("firstName"), in.get("lastName"), in.get("userType"), in.get("password")));
                } else {
                    service.add("ADMIN001", new UserAdminService.NewUser(in.get("firstName"), in.get("lastName"),
                            in.get("userId"), in.get("password"), in.get("userType")));
                }
                actual = "no refusal and no transaction";
            } catch (ApiException e) {
                actual = e.field() + " " + e.code() + " " + e.text() + " (" + e.status().value() + ")";
            } catch (IllegalStateException e) {
                if (e != TRANSACTION_REACHED) {
                    throw e;
                }
                actual = "transaction";
            }
            if (!actual.equals(expected)) {
                differences.add(c.get("name").asString() + ": form " + expected + ", service " + actual);
            }
        }
        assertThat(cases).as(form + " vectors").isGreaterThan(20);
        assertThat(passing).as(form + " vectors that pass").isPositive();
        return differences;
    }

    private static String outcome(FieldProblem p) {
        return p.field() + " " + p.code() + " " + p.message() + " (400)";
    }

    private static UserAdminService service(FidelityMode passwordCase) {
        FidelitySwitches switches = FidelitySwitches.allLegacy(IdentitySwitches.ALL)
                .with(IdentitySwitches.DEF_SGN_011, passwordCase);
        PasswordHasher hasher = mock(PasswordHasher.class);
        when(hasher.hash(anyString())).thenReturn("{argon2}not-checked");
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenThrow(TRANSACTION_REACHED);
        return new UserAdminService(mock(UserRepository.class), mock(SessionRepository.class),
                mock(UserAdminLocks.class), hasher, new IdentityFidelity(switches), mock(AuditLog.class),
                Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC), tx);
    }
}
