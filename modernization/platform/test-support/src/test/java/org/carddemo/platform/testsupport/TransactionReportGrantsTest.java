package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * R__grants_transaction.sql for transaction reports: transaction_svc requests a report run through
 * batch.report_run_request, reads its status and its committed sha256 and records a trigger
 * failure, and nothing else. V424's checks: only a COMPLETED row carries a hash, and every row
 * completed from then on has one.
 */
class TransactionReportGrantsTest {

    private static final long EXECUTION = -7;
    private static final String REQUESTER = "TRNGRNT1";
    private static final String HASH = "HASHBYTES('SHA2_256', 'report')";

    private final TestDatabase db = TestDatabase.shared();
    private final JdbcClient admin = db.admin();
    private final DataSource service = db.dataSource(ServiceLogin.TRANSACTION);
    private final JdbcClient svc = JdbcClient.create(service);

    @BeforeEach
    @AfterEach
    void clean() {
        admin.sql("DELETE FROM batch.report_run WHERE requested_by = :u").param("u", REQUESTER).update();
        admin.sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :e").param("e", EXECUTION).update();
        admin.sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :e").param("e", EXECUTION).update();
    }

    @Test
    void theServiceRequestsReadsAndFailsTheTriggerOfARun() throws SQLException {
        Map<String, Object> request = svc.sql("""
                EXEC batch.report_run_request @report_type = 'CUSTOM', @start_date = '2022-07-01',
                    @end_date = '2022-07-06', @requested_by = :u""")
                .param("u", REQUESTER).query().singleRow();
        long run = ((Number) request.get("report_run_id")).longValue();
        Map<String, Object> requested = row(run);
        assertThat(requested).containsEntry("status", "REQUESTED").containsEntry("failure_code", null)
                .containsEntry("started_at", null).containsEntry("finished_at", null)
                .containsEntry("output_sha256", null);
        assertThat(requested.get("requested_at")).isNotNull().isEqualTo(request.get("requested_at"));
        assertThat(admin.sql("SELECT job_execution_id FROM batch.report_run WHERE report_run_id = :id")
                .param("id", run).query().singleRow().get("job_execution_id")).isNull();
        assertThat(svc.sql("SELECT stalled FROM batch.v_report_run_stalled WHERE report_run_id = :id")
                .param("id", run).query(Boolean.class).single()).isFalse();

        assertThat(triggerFailed(run)).isOne();
        Map<String, Object> failed = row(run);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("failure_code", "TRIGGER_FAILED");
        assertThat(failed.get("finished_at")).isNotNull();
        assertThat(triggerFailed(run)).isZero();
        assertThat(row(run)).isEqualTo(failed);
    }

    @Test
    void aTriggerFailureLeavesARunThatStartedOrEndedAlone() throws SQLException {
        createExecution();
        for (String state : new String[] {
                "'RUNNING', SYSUTCDATETIME(), NULL, NULL, NULL",
                "'COMPLETED', SYSUTCDATETIME(), SYSUTCDATETIME(), NULL, " + HASH,
                "'FAILED', SYSUTCDATETIME(), SYSUTCDATETIME(), 'JOB_FAILED', NULL"}) {
            long run = insert(state);
            Map<String, Object> before = row(run);
            assertThat(triggerFailed(run)).as(state).isZero();
            assertThat(row(run)).as(state).isEqualTo(before);
        }
        assertThat(triggerFailed(Long.MAX_VALUE)).isZero();
    }

    /** The service reads the hash the job committed, as the job wrote it. */
    @Test
    void theServiceReadsTheCommittedHash() {
        createExecution();
        long run = insert("'COMPLETED', SYSUTCDATETIME(), SYSUTCDATETIME(), NULL, " + HASH);
        byte[] expected = admin.sql("SELECT " + HASH).query(byte[].class).single();
        assertThat(expected).hasSize(32);
        assertThat(svc.sql("SELECT output_sha256 FROM batch.report_run WHERE report_run_id = :id").param("id", run)
                .query(byte[].class).single()).isEqualTo(expected);
    }

    /** V424: only a COMPLETED row carries a hash, and a run cannot complete without one. */
    @Test
    void onlyACompletedRunCarriesAHashAndEveryNewlyCompletedRunHasOne() {
        createExecution();
        assertThatThrownBy(() -> insert("'COMPLETED', SYSUTCDATETIME(), SYSUTCDATETIME(), NULL, NULL"))
                .hasMessageContaining("CK_report_run_completed_sha256");
        assertThatThrownBy(() -> insert("'RUNNING', SYSUTCDATETIME(), NULL, NULL, " + HASH))
                .hasMessageContaining("CK_report_run_sha256_completed");
        assertThatThrownBy(() -> insert("'FAILED', SYSUTCDATETIME(), SYSUTCDATETIME(), 'JOB_FAILED', " + HASH))
                .hasMessageContaining("CK_report_run_sha256_completed");

        long run = insert("'RUNNING', SYSUTCDATETIME(), NULL, NULL, NULL");
        Map<String, Object> running = row(run);
        String complete = "UPDATE batch.report_run SET status = 'COMPLETED', finished_at = SYSUTCDATETIME()%s"
                + " WHERE report_run_id = :id";
        assertThatThrownBy(() -> admin.sql(complete.formatted("")).param("id", run).update())
                .hasMessageContaining("CK_report_run_completed_sha256");
        assertThat(row(run)).isEqualTo(running);
        assertThat(admin.sql(complete.formatted(", output_sha256 = " + HASH)).param("id", run).update()).isOne();
        Map<String, Object> completed = row(run);
        assertThat(completed).containsEntry("status", "COMPLETED");
        assertThat(completed.get("output_sha256")).isNotNull();
        assertThatThrownBy(() -> admin.sql("UPDATE batch.report_run SET output_sha256 = NULL WHERE report_run_id = :id")
                .param("id", run).update()).hasMessageContaining("CK_report_run_completed_sha256");
    }

    /**
     * The hash on COMPLETED is enforced for every new or changed row, but a row completed before
     * V424 keeps its NULL: the constraint is enabled and not trusted.
     */
    @Test
    void theHashChecksAreEnabledAndOnlyTheOneOnOldRowsIsUntrusted() {
        Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
        admin.sql("""
                        SELECT name, is_disabled, is_not_trusted FROM sys.check_constraints
                        WHERE parent_object_id = OBJECT_ID('batch.report_run')
                          AND name IN ('CK_report_run_sha256_completed', 'CK_report_run_completed_sha256')""")
                .query().listOfRows().forEach(r -> checks.put((String) r.get("name"), r));
        assertThat(checks).containsOnlyKeys("CK_report_run_sha256_completed", "CK_report_run_completed_sha256");
        assertThat(checks.get("CK_report_run_sha256_completed")).containsEntry("is_disabled", false)
                .containsEntry("is_not_trusted", false);
        assertThat(checks.get("CK_report_run_completed_sha256")).containsEntry("is_disabled", false)
                .containsEntry("is_not_trusted", true);
    }

    @Test
    void theLoginIsRefusedItsProbes() throws SQLException {
        assertThat(ServiceLogin.TRANSACTION.forbiddenProbes()).contains(
                "UPDATE batch.report_run SET output_sha256 = NULL WHERE 1 = 0",
                "SELECT TOP 0 * FROM batch.BATCH_JOB_EXECUTION_CONTEXT");
        try (Connection c = service.getConnection(); Statement s = c.createStatement()) {
            for (String probe : ServiceLogin.TRANSACTION.forbiddenProbes()) {
                assertThatThrownBy(() -> s.execute(probe)).as(probe)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isIn(229, 230));
            }
        }
    }

    private void createExecution() {
        admin.sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                + "VALUES (:e, 0, 'reportGrantsTest', 'reportGrantsTest')").param("e", EXECUTION).update();
        admin.sql("INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, "
                + "LAST_UPDATED, STATUS) VALUES (:e, 0, :e, GETUTCDATE(), GETUTCDATE(), 'STARTED')")
                .param("e", EXECUTION).update();
    }

    /** A row of the test execution; {@code state} is status, started_at, finished_at, failure_code, output_sha256. */
    private long insert(String state) {
        return admin.sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, job_execution_id,
                    status, started_at, finished_at, failure_code, output_sha256)
                OUTPUT inserted.report_run_id VALUES ('CUSTOM', '2022-07-01', '2022-07-06', :u, :e, %s)"""
                .formatted(state)).param("u", REQUESTER).param("e", EXECUTION).query(Long.class).single();
    }

    /** The columns the service may read, read as the service, with the binary ones as hex so that rows compare by value. */
    private Map<String, Object> row(long run) {
        Map<String, Object> row = new LinkedHashMap<>(svc.sql("""
                SELECT report_run_id, report_type, start_date, end_date, requested_by, requested_at, status,
                    started_at, finished_at, failure_code, row_version, output_sha256
                FROM batch.report_run WHERE report_run_id = :id""").param("id", run).query().singleRow());
        row.put("row_version", HexFormat.of().formatHex((byte[]) row.get("row_version")));
        if (row.get("output_sha256") != null) {
            row.put("output_sha256", HexFormat.of().formatHex((byte[]) row.get("output_sha256")));
        }
        return row;
    }

    /** Calls the procedure as the service and checks that its result set and return value agree. */
    private int triggerFailed(long run) throws SQLException {
        try (Connection c = service.getConnection();
                CallableStatement call = c.prepareCall("{? = call batch.report_run_trigger_failed(?)}")) {
            call.registerOutParameter(1, Types.INTEGER);
            call.setLong(2, run);
            assertThat(call.execute()).isTrue();
            int changed;
            try (ResultSet rs = call.getResultSet()) {
                assertThat(rs.next()).isTrue();
                changed = rs.getInt("changed");
            }
            assertThat(call.getMoreResults()).isFalse();
            assertThat(call.getInt(1)).isEqualTo(changed);
            return changed;
        }
    }
}
