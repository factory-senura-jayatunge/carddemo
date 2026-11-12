package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The batch range of the schema (V400-V499) as batch_svc sees it. */
class BatchSchemaTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final Path DAILY = Path.of(System.getProperty("carddemo.repo.root", "../../.."),
            "app", "data", "ASCII", "dailytran.txt");

    private final JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));

    @BeforeEach
    void empty() {
        dropTestExecution();
        DB.admin().sql("DELETE FROM ops.batch_window WHERE opened_by = 'schemaTest'").update();
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
        DB.admin().sql("DELETE FROM batch.cycle").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
    }

    private int openCycle(LocalDate businessDate) {
        batch.sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES (:d, :p)")
                .param("d", businessDate).param("p", businessDate.toString().replace("-", "") + "00").update();
        return batch.sql("SELECT cycle_id FROM batch.cycle WHERE business_date = :d").param("d", businessDate)
                .query(Integer.class).single();
    }

    @Test
    void oneCyclePerBusinessDate() {
        openCycle(LocalDate.of(2022, 7, 6));
        assertThatThrownBy(() -> openCycle(LocalDate.of(2022, 7, 6))).hasMessageContaining("UQ_cycle_business_date");
        assertThatThrownBy(() -> batch.sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES ('2022-07-07', '2022070712')")
                .update()).hasMessageContaining("CK_cycle_interest_parm");
    }

    @Test
    void aCycleIsOpenUntilCompletedAndCountsItsAttempts() {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        var row = batch.sql("SELECT status, attempt, closed_at, daily_file_name FROM batch.cycle WHERE cycle_id = :c")
                .param("c", cycle).query().singleRow();
        assertThat(row).containsEntry("status", "OPEN").containsEntry("attempt", 1);
        assertThat(row.get("closed_at")).isNull();
        assertThat(row.get("daily_file_name")).isNull();

        assertThatThrownBy(() -> batch.sql("UPDATE batch.cycle SET status = 'RUNNING' WHERE cycle_id = :c")
                .param("c", cycle).update()).hasMessageContaining("CK_cycle_status");
        assertThatThrownBy(() -> batch.sql("UPDATE batch.cycle SET status = 'COMPLETED' WHERE cycle_id = :c")
                .param("c", cycle).update()).hasMessageContaining("CK_cycle_status");
        assertThat(batch.sql("UPDATE batch.cycle SET status = 'COMPLETED', closed_at = SYSUTCDATETIME() WHERE cycle_id = :c")
                .param("c", cycle).update()).isEqualTo(1);
        // cycle-rerun: OPEN again with the next attempt.
        assertThat(batch.sql("UPDATE batch.cycle SET status = 'OPEN', closed_at = NULL, attempt = attempt + 1 WHERE cycle_id = :c")
                .param("c", cycle).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql("UPDATE batch.cycle SET attempt = 0 WHERE cycle_id = :c")
                .param("c", cycle).update()).hasMessageContaining("CK_cycle_attempt");

        assertThatThrownBy(() -> batch.sql("UPDATE batch.cycle SET daily_file_name = '2022-07-06.txt' WHERE cycle_id = :c")
                .param("c", cycle).update()).hasMessageContaining("CK_cycle_daily_file");
        assertThat(batch.sql("""
                UPDATE batch.cycle SET daily_file_name = '2022-07-06.txt',
                    daily_file_sha256 = HASHBYTES('SHA2_256', 'x') WHERE cycle_id = :c""")
                .param("c", cycle).update()).isEqualTo(1);
    }

    /** Job instance and execution -1 and every row that refers to them. */
    private static void dropTestExecution() {
        DB.admin().sql("DELETE FROM batch.report_run WHERE job_execution_id = -1 OR requested_by = 'SCHEMAT1'").update();
        DB.admin().sql("DELETE FROM batch.export_run WHERE job_instance_id = -1").update();
        DB.admin().sql("DELETE FROM batch.execution_lease WHERE job_execution_id = -1").update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = -1").update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = -1").update();
    }

    private static void createTestExecution(String status) {
        DB.admin().sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                + "VALUES (-1, 0, 'schemaTest', 'schemaTest')").update();
        DB.admin().sql("INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, "
                + "LAST_UPDATED, STATUS) VALUES (-1, 0, -1, GETUTCDATE(), GETUTCDATE(), :s)").param("s", status).update();
    }

    @Test
    void anExecutionLeaseBelongsToAJobExecutionAndIsRevokedWithAReason() {
        String lease = """
                INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid, recoverable_after_ms)
                VALUES (-1, NEWID(), 'host', 1, 120000)""";
        assertThatThrownBy(() -> batch.sql(lease).update()).hasMessageContaining("FK_execution_lease_execution");

        createTestExecution("STARTED");
        assertThatThrownBy(() -> batch.sql(lease.replace("120000", "0")).update())
                .hasMessageContaining("CK_execution_lease_recoverable_after");
        assertThat(batch.sql(lease).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql("UPDATE batch.execution_lease SET recoverable_after_ms = 60000 "
                + "WHERE job_execution_id = -1").update()).hasMessageContaining("recoverable_after_ms never changes");
        assertThat(batch.sql("SELECT recoverable_after_ms FROM batch.execution_lease WHERE job_execution_id = -1")
                .query(Integer.class).single()).isEqualTo(120000);
        var row = batch.sql("SELECT acquired_at, renewed_at, revoked_at FROM batch.execution_lease WHERE job_execution_id = -1")
                .query().singleRow();
        assertThat(row.get("acquired_at")).isNotNull();
        assertThat(row.get("renewed_at")).isNotNull();
        assertThat(row.get("revoked_at")).isNull();
        assertThatThrownBy(() -> batch.sql("UPDATE batch.execution_lease SET revoked_at = SYSUTCDATETIME() "
                + "WHERE job_execution_id = -1").update()).hasMessageContaining("CK_execution_lease_revoked");
        assertThat(batch.sql("UPDATE batch.execution_lease SET revoked_at = SYSUTCDATETIME(), revoked_reason = 'OWNER_LOST' "
                + "WHERE job_execution_id = -1").update()).isEqualTo(1);
        dropTestExecution();
    }

    private long requestReport() {
        return batch.sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)
                OUTPUT inserted.report_run_id VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'SCHEMAT1')""")
                .query(Long.class).single();
    }

    private Map<String, Object> reportRow(long id) {
        return DB.admin().sql("SELECT status, failure_code, job_execution_id, finished_at, row_version "
                + "FROM batch.report_run WHERE report_run_id = :id").param("id", id).query().singleRow();
    }

    private static int triggerFailed(long id) throws SQLException {
        try (Connection c = DB.adminDataSource().getConnection();
                CallableStatement call = c.prepareCall("{? = call batch.report_run_trigger_failed(?)}")) {
            call.registerOutParameter(1, Types.INTEGER);
            call.setLong(2, id);
            assertThat(call.execute()).as("one result set").isTrue();
            int changed;
            try (ResultSet rs = call.getResultSet()) {
                assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
                assertThat(rs.getMetaData().getColumnLabel(1)).isEqualTo("changed");
                assertThat(rs.next()).isTrue();
                changed = rs.getInt(1);
                assertThat(rs.next()).isFalse();
            }
            assertThat(call.getMoreResults()).isFalse();
            assertThat(call.getInt(1)).as("the return value is the result").isEqualTo(changed);
            return changed;
        }
    }

    @Test
    void aReportRunTiesEachStatusToItsColumns() {
        createTestExecution("STARTED");
        long run = requestReport();
        assertThat(reportRow(run)).containsEntry("status", "REQUESTED");
        String set = "UPDATE batch.report_run SET %s WHERE report_run_id = :id";
        for (String bad : new String[] {
                "status = 'RUNNING'",
                "status = 'COMPLETED', job_execution_id = -1, started_at = SYSUTCDATETIME(),"
                        + " output_sha256 = HASHBYTES('SHA2_256', 'report')",
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), job_execution_id = -1",
                "status = 'REQUESTED', job_execution_id = -1"}) {
            assertThatThrownBy(() -> batch.sql(set.formatted(bad)).param("id", run).update()).as(bad)
                    .hasMessageContaining("CK_report_run_status");
        }
        assertThatThrownBy(() -> batch.sql(set.formatted(
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'BOOM', job_execution_id = -1"))
                .param("id", run).update()).hasMessageContaining("CK_report_run_failure_code");
        assertThatThrownBy(() -> batch.sql(set.formatted(
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'JOB_FAILED'"))
                .param("id", run).update()).as("a FAILED row without an execution is a trigger failure only")
                .hasMessageContaining("CK_report_run_trigger_failed");
        assertThatThrownBy(() -> batch.sql(set.formatted(
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'TRIGGER_FAILED', job_execution_id = -1"))
                .param("id", run).update()).hasMessageContaining("CK_report_run_trigger_failed");
        assertThat(batch.sql(set.formatted("status = 'RUNNING', job_execution_id = -1, started_at = SYSUTCDATETIME()"))
                .param("id", run).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql(set.formatted(
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'TRIGGER_FAILED'"))
                .param("id", run).update()).as("a running row cannot become a trigger failure")
                .hasMessageContaining("CK_report_run_trigger_failed");
        assertThatThrownBy(() -> batch.sql(set.formatted("job_execution_id = -2")).param("id", run).update())
                .hasMessageContaining("FK_report_run_execution");
        assertThatThrownBy(() -> batch.sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)
                VALUES ('DAILY', '2022-07-06', '2022-07-01', 'SCHEMAT1')""").update())
                .hasMessageContaining("CK_report_run_");
    }

    @Test
    void onlyARequestedReportRunCanBeTriggerFailedAndANoOpLeavesTheRowAsItWas() throws SQLException {
        createTestExecution("STARTED");
        long requested = requestReport();
        long running = requestReport();
        long completed = requestReport();
        long failed = requestReport();
        String set = "UPDATE batch.report_run SET %s WHERE report_run_id = :id";
        batch.sql(set.formatted("status = 'RUNNING', job_execution_id = -1, started_at = SYSUTCDATETIME()"))
                .param("id", running).update();
        batch.sql(set.formatted("status = 'COMPLETED', job_execution_id = -1, started_at = SYSUTCDATETIME(), "
                + "finished_at = SYSUTCDATETIME(), output_sha256 = HASHBYTES('SHA2_256', 'report')")).param("id", completed)
                .update();
        batch.sql(set.formatted("status = 'FAILED', job_execution_id = -1, started_at = SYSUTCDATETIME(), "
                + "finished_at = SYSUTCDATETIME(), failure_code = 'JOB_FAILED'")).param("id", failed).update();

        for (long id : new long[] {running, completed, failed}) {
            Map<String, Object> before = reportRow(id);
            assertThat(triggerFailed(id)).isZero();
            Map<String, Object> after = reportRow(id);
            assertThat(after).containsAllEntriesOf(Map.of("status", before.get("status")));
            assertThat((byte[]) after.get("row_version")).as("rowversion of report run " + id)
                    .isEqualTo((byte[]) before.get("row_version"));
        }
        assertThat(triggerFailed(Long.MAX_VALUE)).as("an unknown run").isZero();

        assertThat(triggerFailed(requested)).isEqualTo(1);
        Map<String, Object> row = reportRow(requested);
        assertThat(row).containsEntry("status", "FAILED").containsEntry("failure_code", "TRIGGER_FAILED");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("job_execution_id")).isNull();
        assertThat(triggerFailed(requested)).as("a second call").isZero();
    }

    @Test
    void aRunningReportRunIsStalledOnlyWhenItsExecutionIsRunningAndItsLeaseRecoverable() {
        createTestExecution("STARTED");
        long run = requestReport();
        String stalled = "SELECT stalled FROM batch.v_report_run_stalled WHERE report_run_id = :id";
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("REQUESTED").isFalse();
        batch.sql("UPDATE batch.report_run SET status = 'RUNNING', job_execution_id = -1, started_at = SYSUTCDATETIME() "
                + "WHERE report_run_id = :id").param("id", run).update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("no lease, just updated").isFalse();
        DB.admin().sql("UPDATE batch.BATCH_JOB_EXECUTION SET LAST_UPDATED = DATEADD(SECOND, -121, GETUTCDATE()) "
                + "WHERE JOB_EXECUTION_ID = -1").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("no lease, 121 s old").isTrue();

        batch.sql("""
                INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid, recoverable_after_ms)
                VALUES (-1, NEWID(), 'host', 1, 4000)""").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("renewed now").isFalse();
        batch.sql("UPDATE batch.execution_lease SET renewed_at = DATEADD(MILLISECOND, -3000, SYSUTCDATETIME()) "
                + "WHERE job_execution_id = -1").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("within TTL + grace").isFalse();
        batch.sql("UPDATE batch.execution_lease SET renewed_at = DATEADD(MILLISECOND, -5000, SYSUTCDATETIME()) "
                + "WHERE job_execution_id = -1").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("past TTL + grace").isTrue();
        batch.sql("UPDATE batch.execution_lease SET revoked_at = SYSUTCDATETIME(), revoked_reason = 'OWNER_LOST' "
                + "WHERE job_execution_id = -1").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("revoked").isFalse();
        batch.sql("UPDATE batch.execution_lease SET revoked_at = NULL, revoked_reason = NULL WHERE job_execution_id = -1")
                .update();
        DB.admin().sql("UPDATE batch.BATCH_JOB_EXECUTION SET STATUS = 'FAILED' WHERE JOB_EXECUTION_ID = -1").update();
        assertThat(batch.sql(stalled).param("id", run).query(Boolean.class).single()).as("execution ended").isFalse();
    }

    @Test
    void anExportRunBelongsToOneJobInstance() {
        String insert = "INSERT INTO batch.export_run (job_instance_id, job_execution_id, business_date) "
                + "VALUES (:i, -1, '2022-07-06')";
        assertThatThrownBy(() -> batch.sql(insert).param("i", -1).update()).hasMessageContaining("FK_export_run_");
        createTestExecution("STARTED");
        assertThat(batch.sql(insert).param("i", -1).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql(insert).param("i", -1).update())
                .hasMessageContaining("UQ_export_run_job_instance");
        String set = "UPDATE batch.export_run SET %s WHERE job_instance_id = -1";
        assertThat(batch.sql("SELECT status FROM batch.export_run WHERE job_instance_id = -1").query(String.class).single())
                .isEqualTo("RUNNING");
        assertThatThrownBy(() -> batch.sql(set.formatted("status = 'COMPLETED'")).update())
                .hasMessageContaining("CK_export_run_status");
        assertThatThrownBy(() -> batch.sql(set.formatted("status = 'FAILED', finished_at = SYSUTCDATETIME()")).update())
                .hasMessageContaining("CK_export_run_status");
        assertThatThrownBy(() -> batch.sql(set.formatted(
                "status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'TRIGGER_FAILED'")).update())
                .hasMessageContaining("CK_export_run_failure_code");
        assertThat(batch.sql(set.formatted("status = 'FAILED', finished_at = SYSUTCDATETIME(), failure_code = 'OWNER_LOST'"))
                .update()).isEqualTo(1);
    }

    @Test
    void aBatchWindowNamesExactlyOneHolder() {
        String insert = "INSERT INTO ops.batch_window (cycle_id, run_key, mode, opened_by, closed_at) "
                + "VALUES (:c, :k, 'WRITES_BLOCKED', 'schemaTest', SYSUTCDATETIME())";
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        assertThatThrownBy(() -> DB.admin().sql(insert).param("c", null).param("k", null).update())
                .hasMessageContaining("CK_batch_window_holder");
        assertThatThrownBy(() -> DB.admin().sql(insert).param("c", cycle).param("k", "cli/h/t/u").update())
                .hasMessageContaining("CK_batch_window_holder");
        assertThatThrownBy(() -> DB.admin().sql(insert).param("c", null).param("k", "").update())
                .hasMessageContaining("CK_batch_window_run_key");
        assertThat(DB.admin().sql(insert).param("c", cycle).param("k", null).update()).isEqualTo(1);
        assertThat(DB.admin().sql(insert).param("c", null).param("k", "refresh_reference/manual__1").update()).isEqualTo(1);
        DB.admin().sql("DELETE FROM ops.batch_window WHERE opened_by = 'schemaTest'").update();
    }

    @Test
    void transactionsAndBatchWindowsReferToAnExistingCycle() {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card("4111111111111111", "00000000001", "000000001")
                .transaction("0000000000000001", "4111111111111111", "1.00");
        assertThat(DB.admin().sql("UPDATE core.[transaction] SET cycle_id = :c").param("c", cycle).update()).isEqualTo(1);
        assertThatThrownBy(() -> DB.admin().sql("UPDATE core.[transaction] SET cycle_id = :c").param("c", cycle + 1).update())
                .hasMessageContaining("FK_transaction_cycle");
        assertThatThrownBy(() -> DB.admin().sql(
                        "INSERT INTO ops.batch_window (cycle_id, mode, opened_by) VALUES (:c, 'WRITES_BLOCKED', 'test')")
                .param("c", cycle + 1).update())
                .hasMessageContaining("FK_batch_window_cycle");
    }

    /** V420: exactly one marker row, with an ID, that no second row can join. */
    @Test
    void theDatabaseInstanceMarkerIsOneRowWithAnId() {
        Map<String, Object> row = DB.admin().sql("SELECT id, instance_id, created_at FROM ops.database_instance")
                .query().singleRow();
        assertThat(((Number) row.get("id")).intValue()).isEqualTo(1);
        assertThat(row.get("instance_id")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThatThrownBy(() -> DB.admin().sql("INSERT INTO ops.database_instance (id, instance_id) VALUES (2, NEWID())")
                .update()).hasMessageContaining("CK_database_instance_id");
        assertThatThrownBy(() -> DB.admin().sql("INSERT INTO ops.database_instance (id, instance_id) VALUES (1, NEWID())")
                .update()).hasMessageContaining("PK_database_instance");
        assertThatThrownBy(() -> DB.admin().sql("UPDATE ops.database_instance SET instance_id = NULL").update())
                .hasMessageContaining("instance_id");
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.database_instance").query(Integer.class).single())
                .isEqualTo(1);
    }

    /** A column of stage 1 of the text repertoire (V411, V421) as the catalog must show it. */
    private record TextColumn(String table, String column, String type, int chars, boolean bin2, Boolean nullable,
            String defaultName, String mask) {
    }

    private static final String BIN2 = "Latin1_General_100_BIN2";
    private static final String DATABASE_DEFAULT = "Latin1_General_100_CI_AS_SC_UTF8";

    private static List<TextColumn> stage1Columns() {
        List<TextColumn> columns = new ArrayList<>(List.of(
                new TextColumn("core.account", "group_id", "nchar", 10, true, false, "DF_account_group_id", null),
                new TextColumn("core.account", "addr_zip", "nvarchar", 10, false, false, "DF_account_addr_zip", null),
                new TextColumn("core.account", "legacy_filler", "nchar", 178, true, true, null, null),
                new TextColumn("core.customer", "addr_line_1", "nvarchar", 50, false, false, null, null),
                new TextColumn("core.customer", "addr_line_2", "nvarchar", 50, false, false, null, null),
                new TextColumn("core.customer", "govt_issued_id", "nvarchar", 20, false, false, null, "default()"),
                new TextColumn("core.card", "legacy_filler", "nchar", 59, true, true, null, null),
                new TextColumn("core.tcat_bal", "legacy_filler", "nchar", 22, true, true, null, null),
                new TextColumn("core.[transaction]", "legacy_filler", "nchar", 20, true, true, null, null),
                new TextColumn("core.daily_tran", "raw_record", "nchar", 350, true, false, null, null),
                new TextColumn("core.daily_reject", "raw_record", "nchar", 350, true, false, null, null),
                // A view column's nullability is SQL Server's inference, not part of the definition.
                new TextColumn("core.v_daily_reject", "raw_record_masked", "nchar", 350, true, null, null, null),
                // The snapshot of core.tcat_bal's FILLER (V412) holds what that column holds.
                new TextColumn("batch.snapshot_tcat_bal", "legacy_filler", "nchar", 22, true, true, null, null),
                new TextColumn("ref.tran_type", "description", "nvarchar", 50, false, false, null, null),
                new TextColumn("ref.tran_category", "description", "nvarchar", 50, false, false, null, null)));
        for (String table : new String[] {"core.[transaction]", "core.daily_tran"}) {
            columns.add(new TextColumn(table, "source", "nvarchar", 10, false, false, null, null));
            columns.add(new TextColumn(table, "description", "nvarchar", 100, false, false, null, null));
            columns.add(new TextColumn(table, "merchant_name", "nvarchar", 50, false, false, null, null));
            columns.add(new TextColumn(table, "merchant_city", "nvarchar", 50, false, false, null, null));
            columns.add(new TextColumn(table, "merchant_zip", "nvarchar", 10, false, false, null, null));
        }
        return columns;
    }

    @Test
    void theStage1TextColumnsHoldTheLegacyRepertoireWithTheirDefaultsAndMasks() {
        for (TextColumn c : stage1Columns()) {
            Map<String, Object> row = DB.admin().sql("""
                    SELECT t.name AS type, c.max_length, c.collation_name, c.is_nullable, c.is_masked,
                           m.masking_function, d.name AS default_name, d.definition
                    FROM sys.columns AS c
                    JOIN sys.types AS t ON t.user_type_id = c.user_type_id
                    LEFT JOIN sys.masked_columns AS m ON m.object_id = c.object_id AND m.column_id = c.column_id
                    LEFT JOIN sys.default_constraints AS d ON d.object_id = c.default_object_id
                    WHERE c.object_id = OBJECT_ID(:table) AND c.name = :column""")
                    .param("table", c.table()).param("column", c.column()).query().singleRow();
            String what = c.table() + "." + c.column();
            assertThat(row.get("type")).as(what).isEqualTo(c.type());
            assertThat(((Number) row.get("max_length")).intValue()).as(what + " bytes").isEqualTo(2 * c.chars());
            assertThat(row.get("collation_name")).as(what).isEqualTo(c.bin2() ? BIN2 : DATABASE_DEFAULT);
            if (c.nullable() != null) {
                assertThat(row.get("is_nullable")).as(what + " nullable").isEqualTo(c.nullable());
            }
            assertThat(row.get("default_name")).as(what + " default").isEqualTo(c.defaultName());
            if (c.defaultName() != null) {
                assertThat(row.get("definition")).as(what).isEqualTo("(N'')");
            }
            assertThat(row.get("is_masked")).as(what + " masked").isEqualTo(c.mask() != null);
            assertThat(row.get("masking_function")).as(what).isEqualTo(c.mask());
        }
    }

    /**
     * V418 (DEF-ACUP-002 ruling v4): the provenance of positions 103-122 is a pair, both NULL for
     * a row not loaded from a file, and legacy_zip_group holds the 20 characters as read,
     * U+0000 and trailing spaces included.
     */
    @Test
    void theAccountZipGroupProvenanceIsAPairThatKeepsTheTwentyCharactersAsRead() {
        Map<String, Map<String, Object>> columns = new HashMap<>();
        DB.admin().sql("""
                SELECT c.name, t.name AS type, c.max_length, c.collation_name, c.is_nullable, c.default_object_id
                FROM sys.columns AS c JOIN sys.types AS t ON t.user_type_id = c.user_type_id
                WHERE c.object_id = OBJECT_ID('core.account') AND c.name IN ('legacy_zip_group', 'zip_group_origin')""")
                .query().listOfRows().forEach(r -> columns.put((String) r.get("name"), r));
        assertThat(columns.get("legacy_zip_group")).containsEntry("type", "nchar").containsEntry("max_length", (short) 40)
                .containsEntry("collation_name", BIN2).containsEntry("is_nullable", true)
                .containsEntry("default_object_id", 0);
        assertThat(columns.get("zip_group_origin")).containsEntry("type", "char").containsEntry("max_length", (short) 1)
                .containsEntry("collation_name", BIN2).containsEntry("is_nullable", true)
                .containsEntry("default_object_id", 0);

        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00"));
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.account WHERE legacy_zip_group IS NULL AND"
                + " zip_group_origin IS NULL").query(Integer.class).single()).isOne();
        String update = "UPDATE core.account SET legacy_zip_group = :g, zip_group_origin = :o";
        assertThatThrownBy(() -> DB.admin().sql(update).param("g", null).param("o", "R").update())
                .hasMessageContaining("CK_account_zip_group_pair");
        assertThatThrownBy(() -> DB.admin().sql(update).param("g", "A000000000" + " ".repeat(10)).param("o", null)
                .update()).hasMessageContaining("CK_account_zip_group_pair");
        assertThatThrownBy(() -> DB.admin().sql(update).param("g", "A000000000" + " ".repeat(10)).param("o", "r")
                .update()).hasMessageContaining("CK_account_zip_group_origin");
        String asRead = "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u00ff\u0080A   ";
        String twenty = asRead + " ".repeat(20 - asRead.length());
        assertThat(DB.admin().sql(update).param("g", twenty).param("o", "M").update()).isOne();
        assertThat(DB.admin().sql("SELECT legacy_zip_group FROM core.account").query(String.class).single())
                .isEqualTo(twenty);
        assertThat(DB.admin().sql("""
                SELECT COUNT(*) FROM sys.index_columns AS ic JOIN sys.columns AS c
                    ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                WHERE ic.object_id = OBJECT_ID('core.account') AND c.name IN ('legacy_zip_group', 'zip_group_origin')""")
                .query(Integer.class).single()).as("no index").isZero();
    }
    /** Every one of the 256 characters a legacy byte maps to, wrapping to fill a 350-byte record. */
    private static String everyByteRecord() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 350; i++) {
            sb.append((char) (i % 256));
        }
        return sb.toString();
    }

    @Test
    void aRecordOfEveryByteValueIsStoredAndMaskedUnchanged() {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = everyByteRecord();
        long pk = stage(cycle, 1, "DAILY_FILE", raw);
        batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, 1, :pk, '0100', 'INVALID CARD NUMBER FOUND', '0100', :raw)""")
                .param("c", cycle).param("pk", pk).param("raw", raw).update();

        assertThat(batch.sql("SELECT raw_record FROM core.daily_tran").query(String.class).single()).isEqualTo(raw);
        assertThat(batch.sql("SELECT raw_record FROM core.daily_reject").query(String.class).single()).isEqualTo(raw);
        assertThat(batch.sql("SELECT raw_record_masked FROM core.v_daily_reject").query(String.class).single())
                .isEqualTo(raw.substring(0, 262) + "XXXXXXXXXXXX" + raw.substring(274));
    }

    /** {@code n} descriptions of 50 characters that run through U+0000 to U+00FF in order, wrapping. */
    private static List<String> everyCharacterDescriptions(int n) {
        List<String> out = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            StringBuilder sb = new StringBuilder();
            for (int i = d * 50; i < (d + 1) * 50; i++) {
                sb.append((char) (i % 256));
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * V421: a description holds 50 of any of the 256 characters a legacy byte maps to, written by
     * batch_svc and read back unchanged by batch_svc and by transaction_svc, which serves them
     * online. A 51st character is refused, never cut.
     */
    @Test
    void aTransactionTypeOrCategoryDescriptionHoldsFiftyOfAnyLegacyCharacter() {
        List<String> descriptions = everyCharacterDescriptions(6);
        JdbcClient transaction = JdbcClient.create(DB.dataSource(ServiceLogin.TRANSACTION));
        String types = "SELECT description FROM ref.tran_type WHERE type_cd LIKE 'Z_' ORDER BY type_cd";
        String categories = "SELECT description FROM ref.tran_category WHERE type_cd = 'Z0' ORDER BY cat_cd";
        try {
            for (int i = 0; i < descriptions.size(); i++) {
                batch.sql("INSERT INTO ref.tran_type (type_cd, description) VALUES (:t, :d)")
                        .param("t", "Z" + i).param("d", descriptions.get(i)).update();
                batch.sql("INSERT INTO ref.tran_category (type_cd, cat_cd, description) VALUES ('Z0', :c, :d)")
                        .param("c", "%04d".formatted(i + 1)).param("d", descriptions.get(i)).update();
            }
            for (JdbcClient reader : List.of(batch, transaction)) {
                assertThat(reader.sql(types).query(String.class).list()).containsExactlyElementsOf(descriptions);
                assertThat(reader.sql(categories).query(String.class).list()).containsExactlyElementsOf(descriptions);
            }
            String tooLong = descriptions.get(4) + "\u00ff";
            assertThatThrownBy(() -> batch.sql("INSERT INTO ref.tran_type (type_cd, description) VALUES ('Z9', :d)")
                    .param("d", tooLong).update()).hasMessageContaining("truncated");
            assertThatThrownBy(() -> batch.sql(
                    "INSERT INTO ref.tran_category (type_cd, cat_cd, description) VALUES ('Z0', '0099', :d)")
                    .param("d", tooLong).update()).hasMessageContaining("truncated");
        } finally {
            DB.admin().sql("DELETE FROM ref.tran_category WHERE type_cd LIKE 'Z_'").update();
            DB.admin().sql("DELETE FROM ref.tran_type WHERE type_cd LIKE 'Z_'").update();
        }
    }
    private long stage(Integer cycle, Integer seq, String origin, String raw) {
        batch.sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts,
                    proc_ts, raw_record)
                VALUES (:c, :s, :o, :u, :id, '01', '0001', 'POS TERM', 'TEST', 1.00, '000000001', 'M', 'C', 'Z',
                    :card, :ts, '', :raw)""")
                .param("c", cycle).param("s", seq).param("o", origin)
                .param("u", origin.equals("ONLINE_ADD") ? "USER0001" : null)
                .param("id", raw.substring(0, 16)).param("card", raw.substring(262, 278))
                .param("ts", raw.substring(278, 304)).param("raw", raw).update();
        return batch.sql("SELECT MAX(daily_pk) FROM core.daily_tran").query(Long.class).single();
    }

    private static String daily(int line) throws Exception {
        return Files.readAllLines(DAILY, StandardCharsets.ISO_8859_1).get(line);
    }

    @Test
    void onlineAddsWaitUntilACycleClaimsThem() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        long waiting = stage(null, null, "ONLINE_ADD", daily(1));

        assertThatThrownBy(() -> stage(null, null, "DAILY_FILE", raw)).hasMessageContaining("CK_daily_tran_waiting");
        assertThatThrownBy(() -> stage(null, null, "ONLINE_ADD", daily(1))).hasMessageContaining("UX_daily_waiting");
        assertThatThrownBy(() -> stage(cycle, null, "DAILY_FILE", raw)).hasMessageContaining("CK_daily_tran_claimed");
        stage(cycle, 1, "DAILY_FILE", raw);
        assertThatThrownBy(() -> stage(cycle, 1, "DAILY_FILE", daily(2))).hasMessageContaining("UX_daily_claimed");

        assertThat(batch.sql("UPDATE core.daily_tran SET cycle_id = :c, seq = 2 WHERE daily_pk = :pk")
                .param("c", cycle).param("pk", waiting).update()).isEqualTo(1);
        assertThatThrownBy(() -> batch.sql("UPDATE core.daily_tran SET amount = 0 WHERE daily_pk = :pk")
                .param("pk", waiting).update()).hasMessageContaining("permission was denied");
        // Once claimed, the same ID may wait again (the filtered index covers waiting rows only).
        stage(null, null, "ONLINE_ADD", daily(1));
    }

    private void stageKeyed(Integer cycle, Integer seq, String origin, String user, String tranId, UUID key)
            throws Exception {
        String raw = tranId + daily(1).substring(16);
        batch.sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, idempotency_key, tran_id, type_cd,
                    cat_cd, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_zip,
                    card_num, orig_ts, proc_ts, raw_record)
                VALUES (:c, :s, :o, :u, CAST(:k AS UNIQUEIDENTIFIER), :id, '01', '0001', 'POS TERM', 'TEST', 1.00,
                    '000000001', 'M', 'C', 'Z', :card, :ts, '', :raw)""")
                .param("c", cycle).param("s", seq).param("o", origin).param("u", user)
                .param("k", key == null ? null : key.toString()).param("id", tranId)
                .param("card", raw.substring(262, 278)).param("ts", raw.substring(278, 304)).param("raw", raw)
                .update();
    }

    @Test
    void anIdempotencyKeyIsUniquePerUserAndOnlyOnlineAddsCarryOne() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        UUID key = UUID.randomUUID();
        stageKeyed(null, null, "ONLINE_ADD", "USER0001", "9000000000000001", key);

        assertThatThrownBy(() -> stageKeyed(null, null, "ONLINE_ADD", "USER0001", "9000000000000002", key))
                .hasMessageContaining("UX_daily_idempotency");
        stageKeyed(null, null, "ONLINE_ADD", "USER0002", "9000000000000003", key);
        stageKeyed(null, null, "ONLINE_ADD", "USER0001", "9000000000000004", null);
        stageKeyed(null, null, "ONLINE_ADD", "USER0001", "9000000000000005", null);
        assertThatThrownBy(() -> stageKeyed(cycle, 1, "DAILY_FILE", null, "0000000000000006", UUID.randomUUID()))
                .hasMessageContaining("CK_daily_tran_idempotency");
        assertThat(batch.sql("SELECT COUNT(*) FROM core.daily_tran WHERE idempotency_key = CAST(:k AS UNIQUEIDENTIFIER)")
                .param("k", key.toString()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void rejectViewMasksTheCardNumberInTheRecord() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        String card = raw.substring(262, 278);
        long pk = stage(cycle, 1, "DAILY_FILE", raw);
        batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, 1, :pk, '0100', 'INVALID CARD NUMBER FOUND', '0100', :raw)""")
                .param("c", cycle).param("pk", pk).param("raw", raw).update();

        assertThat(batch.sql("SELECT card_num FROM core.daily_tran").query(String.class).single())
                .as("batch_svc reads the card number unmasked").isEqualTo(card);
        var row = batch.sql("SELECT raw_record_masked, origin FROM core.v_daily_reject").query().singleRow();
        String masked = (String) row.get("raw_record_masked");
        assertThat(row.get("origin")).isEqualTo("DAILY_FILE");
        assertThat(masked).hasSize(350).doesNotContain(card);
        assertThat(masked.substring(262, 278)).isEqualTo("XXXXXXXXXXXX" + card.substring(12));
        assertThat(masked.substring(0, 262)).isEqualTo(raw.substring(0, 262));
        batch.sql("DELETE FROM core.daily_reject").update();
        assertThatThrownBy(() -> batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, 1, :pk, '0104', 'X', '0104', :raw)""").param("c", cycle).param("pk", pk).param("raw", raw).update())
                .hasMessageContaining("CK_daily_reject_reason");
    }

    @Test
    void aRejectKeepsEveryFailingReasonBesidesTheLegacyOne() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        String insert = """
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, :s, :pk, :cd, 'X', :cds, :raw)""";
        String[][] accepted = {{"0100", "0100"}, {"0101", "0101"}, {"0102", "0102"}, {"0103", "0103"},
                {"0103", "0102,0103"}, {"0109", "0109"}};
        for (int i = 0; i < accepted.length; i++) {
            long pk = stage(cycle, i + 1, "DAILY_FILE", raw);
            assertThat(batch.sql(insert).param("c", cycle).param("s", i + 1).param("pk", pk)
                    .param("cd", accepted[i][0]).param("cds", accepted[i][1]).param("raw", raw).update())
                    .as(String.join(" ", accepted[i])).isEqualTo(1);
        }
        assertThat(batch.sql("SELECT reason_cds FROM core.v_daily_reject WHERE reason_cd = '0103' ORDER BY seq")
                .query(String.class).list()).containsExactly("0103", "0102,0103");

        // The legacy reason is the last failing check, so 0102 never hides 0103; 100 and 101 stop the checks.
        String[][] refused = {{"0102", "0102,0103"}, {"0100", "0102"}, {"0101", "0101,0102"}, {"0109", "0102,0109"}};
        for (int i = 0; i < refused.length; i++) {
            long pk = stage(cycle, 100 + i, "DAILY_FILE", raw);
            int seq = 100 + i;
            String[] r = refused[i];
            assertThatThrownBy(() -> batch.sql(insert).param("c", cycle).param("s", seq).param("pk", pk)
                    .param("cd", r[0]).param("cds", r[1]).param("raw", raw).update())
                    .as(String.join(" ", r)).hasMessageContaining("CK_daily_reject_reasons");
        }
        long pk = stage(cycle, 200, "DAILY_FILE", raw);
        assertThatThrownBy(() -> batch.sql(insert.replace(":cds", "NULL")).param("c", cycle).param("s", 200)
                .param("pk", pk).param("cd", "0100").param("raw", raw).update())
                .hasMessageContaining("reason_cds");
    }

    /** Every one of the 256 characters a legacy byte maps to, over enough 22-character FILLERs. */
    @Test
    void aCycleSnapshotKeepsTheBalancesAndEveryFillerByte() {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        assertThat(batch.sql("UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME() WHERE cycle_id = :c AND snapshot_at IS NULL")
                .param("c", cycle).update()).isEqualTo(1);
        assertThat(batch.sql("""
                INSERT INTO batch.snapshot_account (cycle_id, acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit)
                VALUES (:c, '00000000001', -9999999999.99, 9999999999.99, -0.01)""").param("c", cycle).update())
                .isEqualTo(1);
        assertThatThrownBy(() -> batch.sql("""
                INSERT INTO batch.snapshot_account (cycle_id, acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit)
                VALUES (:c, '00000000001', 0, 0, 0)""").param("c", cycle).update())
                .hasMessageContaining("PK_snapshot_account");

        List<String> fillers = new ArrayList<>();
        for (int start = 0; start < 256; start += 22) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < start + 22; i++) {
                sb.append((char) (i % 256));
            }
            fillers.add(sb.toString());
        }
        for (int i = 0; i < fillers.size(); i++) {
            batch.sql("""
                    INSERT INTO batch.snapshot_tcat_bal (cycle_id, acct_id, type_cd, cat_cd, balance, legacy_filler)
                    VALUES (:c, '00000000001', '01', :cat, -999999999.99, :f)""")
                    .param("c", cycle).param("cat", "%04d".formatted(i + 1)).param("f", fillers.get(i)).update();
        }
        assertThat(batch.sql("SELECT legacy_filler FROM batch.snapshot_tcat_bal WHERE cycle_id = :c ORDER BY cat_cd")
                .param("c", cycle).query(String.class).list()).containsExactlyElementsOf(fillers);
        assertThat(batch.sql("UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME() WHERE cycle_id = :c AND snapshot_at IS NULL")
                .param("c", cycle).update()).as("taken once per cycle").isZero();
    }

    /**
     * A reader granted only the view, as operations will be: ownership chaining lets it read the
     * masked record, and the tables with the full record stay closed to it.
     */
    @Test
    void aReaderOfTheRejectViewOnlySeesTheMaskedRecord() throws Exception {
        int cycle = openCycle(LocalDate.of(2022, 7, 6));
        String raw = daily(0);
        String card = raw.substring(262, 278);
        long pk = stage(cycle, 1, "DAILY_FILE", raw);
        batch.sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, 1, :pk, '0100', 'INVALID CARD NUMBER FOUND', '0100', :raw)""")
                .param("c", cycle).param("pk", pk).param("raw", raw).update();

        try (Connection c = DB.adminDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("IF USER_ID('reject_view_probe') IS NULL CREATE USER reject_view_probe WITHOUT LOGIN");
            try {
                s.execute("GRANT SELECT ON OBJECT::core.v_daily_reject TO reject_view_probe");
                s.execute("EXECUTE AS USER = 'reject_view_probe'");
                try {
                    try (ResultSet rs = s.executeQuery("SELECT raw_record_masked FROM core.v_daily_reject")) {
                        assertThat(rs.next()).isTrue();
                        String masked = rs.getString(1);
                        assertThat(masked).doesNotContain(card);
                        assertThat(masked.substring(262, 278)).isEqualTo("XXXXXXXXXXXX" + card.substring(12));
                    }
                    for (String query : new String[] {"SELECT raw_record FROM core.daily_reject",
                            "SELECT raw_record FROM core.daily_tran", "SELECT card_num FROM core.daily_tran"}) {
                        assertThatThrownBy(() -> s.executeQuery(query).close()).as(query)
                                .hasMessageContaining("permission was denied");
                    }
                } finally {
                    s.execute("REVERT");
                }
            } finally {
                s.execute("DROP USER reject_view_probe");
            }
        }
    }
}
