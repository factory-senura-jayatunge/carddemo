package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.carddemo.db.EffectiveGrants;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;

/**
 * What each service user can actually read and execute, through the check {@code db-migrate
 * --check-logins} runs ({@link EffectiveGrants}): exactly its tables and columns, and only
 * transaction_svc executes, its four procedures.
 */
class EffectiveSelectGrantsTest {

    private final TestDatabase db = TestDatabase.shared();

    @Test
    void everyServiceUserReadsAndExecutesExactlyItsGrants() throws SQLException {
        try (Connection admin = db.adminDataSource().getConnection()) {
            assertThat(EffectiveGrants.failures(admin)).isEmpty();
        }
    }

    @Test
    void theColumnReadersHoldNoObjectLevelSelect() throws SQLException {
        EffectiveGrants.Effective effective = read();
        for (ServiceLogin login : ServiceLogin.values()) {
            EffectiveGrants.expectedSelect(login, effective.objects()).forEach((object, cols) -> {
                if (!cols.equals(EffectiveGrants.ALL)) {
                    assertThat(effective.select(login).get(object)).as(login.userName() + " " + object)
                            .isNotNull().doesNotContain("*");
                }
            });
        }
    }

    @Test
    void onlyTheTransactionServiceExecutesAndOnlyItsProcedures() throws SQLException {
        EffectiveGrants.Effective effective = read();
        Set<String> procedures = Set.of("batch.report_run_request", "batch.report_run_trigger_failed",
                "core.daily_tran_online_add", "core.daily_tran_key_lookup");
        procedures.forEach(p -> assertThat(effective.objects()).containsEntry(p, "P"));
        for (ServiceLogin login : ServiceLogin.values()) {
            assertThat(effective.execute(login)).as(login.userName())
                    .isEqualTo(login == ServiceLogin.TRANSACTION ? procedures : Set.of());
        }
    }

    /** A grant through a role or PUBLIC, which no probe looks for, is reported; the grants are rolled back. */
    @Test
    void aRoleOrPublicGrantIsReported() throws SQLException {
        List<String> failures;
        try (Connection admin = db.adminDataSource().getConnection()) {
            admin.setAutoCommit(false);
            try (Statement s = admin.createStatement()) {
                s.execute("CREATE ROLE stray_reader");
                s.execute("GRANT SELECT ON ref.nanp_area_code TO stray_reader");
                s.execute("ALTER ROLE stray_reader ADD MEMBER inquiry_svc");
                s.execute("GRANT EXECUTE ON batch.report_run_trigger_failed TO PUBLIC");
                failures = EffectiveGrants.failures(admin);
            } finally {
                admin.rollback();
                admin.setAutoCommit(true);
            }
        }
        List<String> expected = new ArrayList<>();
        expected.add("inquiry_svc can SELECT every column of ref.nanp_area_code instead of nothing");
        for (ServiceLogin login : ServiceLogin.values()) {
            if (login != ServiceLogin.TRANSACTION) {
                expected.add(login.userName() + " can EXECUTE [batch.report_run_trigger_failed] instead of []");
            }
        }
        assertThat(failures).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(read().execute(ServiceLogin.INQUIRY)).isEmpty();
    }

    private EffectiveGrants.Effective read() throws SQLException {
        try (Connection admin = db.adminDataSource().getConnection()) {
            return EffectiveGrants.read(admin);
        }
    }
}
