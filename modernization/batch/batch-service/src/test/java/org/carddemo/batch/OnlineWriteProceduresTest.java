package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.legacyformat.Zoned;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The procedures of V417 through which transaction-service writes core.daily_tran and
 * batch.report_run and checks a replay, called as transaction_svc. Each add runs in a transaction
 * that holds the batch-window lock, as BatchWindowGuard takes it, and is rolled back unless a test
 * needs it committed.
 */
class OnlineWriteProceduresTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final String USER = "PROCTST1";
    private static final String OTHER_USER = "PROCTST2";
    private static final String[] PROCEDURES = {"batch.report_run_request", "core.daily_tran_online_add",
            "core.daily_tran_key_lookup"};
    private static final List<String> grantedHere = new ArrayList<>();

    private final JdbcClient admin = DB.admin();

    /**
     * transaction_svc's EXECUTE grants are backend's (R__grants_transaction.sql); in this module's
     * own database the test grants what is not there yet and takes back only that.
     */
    @BeforeAll
    static void grantExecute() {
        for (String procedure : PROCEDURES) {
            Integer held = DB.admin().sql("""
                    SELECT COUNT(*) FROM sys.database_permissions
                    WHERE grantee_principal_id = USER_ID('transaction_svc') AND class = 1
                      AND major_id = OBJECT_ID(:p) AND permission_name = 'EXECUTE' AND state = 'G'""")
                    .param("p", procedure).query(Integer.class).single();
            if (held == 0) {
                DB.admin().sql("GRANT EXECUTE ON OBJECT::" + procedure + " TO transaction_svc").update();
                grantedHere.add(procedure);
            }
        }
    }

    @AfterAll
    static void revokeExecute() {
        for (String procedure : grantedHere) {
            DB.admin().sql("REVOKE EXECUTE ON OBJECT::" + procedure + " FROM transaction_svc").update();
        }
    }

    @BeforeEach
    void clean() {
        admin.sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        admin.sql("DELETE FROM core.daily_tran WHERE created_by IN (:u)").param("u", List.of(USER, OTHER_USER))
                .update();
        admin.sql("DELETE FROM batch.report_run WHERE requested_by = :u").param("u", USER).update();
        admin.sql("DELETE FROM batch.cycle WHERE business_date = '2101-01-01'").update();
    }

    /** An online add's arguments, as transaction-service normalises them; card numbers are synthetic. */
    private static Map<String, Object> form(String tranId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("created_by", USER);
        f.put("tran_id", tranId);
        f.put("type_cd", "01");
        f.put("cat_cd", "0001");
        f.put("source", "POS TERM");
        f.put("description", "Test purchase");
        f.put("amount", new BigDecimal("-12.50"));
        f.put("merchant_id", "123456789");
        f.put("merchant_name", "Shop");
        f.put("merchant_city", "Town");
        f.put("merchant_zip", "10001");
        f.put("card_num", "0000111122223333");
        f.put("orig_ts", pad("2022-07-06", 26));
        f.put("proc_ts", pad("2026-11-07", 26));
        f.put("idempotency_key", UUID.randomUUID().toString());
        return f;
    }

    private static String pad(String s, int width) {
        return (s + " ".repeat(width)).substring(0, width);
    }

    /** The CVTRA06Y record of the form, with blank FILLER. */
    private static String record(Map<String, Object> f) {
        String amount = Zoned.encode((BigDecimal) f.get("amount"), Layouts.DAILY_TRAN.field("DALYTRAN-AMT").pic());
        return pad(pad((String) f.get("tran_id"), 16) + f.get("type_cd") + f.get("cat_cd") + pad((String) f.get("source"), 10)
                + pad((String) f.get("description"), 100) + amount + f.get("merchant_id")
                + pad((String) f.get("merchant_name"), 50) + pad((String) f.get("merchant_city"), 50)
                + pad((String) f.get("merchant_zip"), 10) + f.get("card_num") + f.get("orig_ts") + f.get("proc_ts"), 350);
    }

    private static Connection asTransactionSvc() throws SQLException {
        Connection c = DB.dataSource(ServiceLogin.TRANSACTION).getConnection();
        c.setAutoCommit(false);
        return c;
    }

    /**
     * Shared and owned by the transaction, as BatchWindowGuard takes it. The connection runs with
     * IMPLICIT_TRANSACTIONS ON, where EXEC opens no transaction and BEGIN TRANSACTION opens two, so
     * a read opens the one transaction that commit() ends.
     */
    private static void holdWindowLock(Connection c) throws SQLException {
        beginTransaction(c);
        try (Statement s = c.createStatement()) {
            s.execute("""
                    EXEC sp_getapplock @Resource = 'carddemo.batch_window', @LockMode = 'Shared',
                        @LockOwner = 'Transaction', @LockTimeout = 5000;""");
        }
    }

    private static void beginTransaction(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeQuery("SELECT TOP 0 tran_id FROM core.daily_tran").close();
            try (ResultSet rs = s.executeQuery("SELECT @@TRANCOUNT")) {
                rs.next();
                assertThat(rs.getInt(1)).as("one open transaction").isEqualTo(1);
            }
        }
    }

    /** Calls the procedure with named arguments; returns the rows of its only result set. */
    private static List<Map<String, Object>> call(Connection c, String procedure, Map<String, Object> args)
            throws SQLException {
        StringBuilder sql = new StringBuilder("EXEC ").append(procedure);
        String separator = " ";
        for (String name : args.keySet()) {
            sql.append(separator).append('@').append(name).append(" = ?");
            separator = ", ";
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object value : args.values()) {
                ps.setObject(i++, value);
            }
            assertThat(ps.execute()).as("the first result is a result set").isTrue();
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.getResultSet()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                        row.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
                    }
                    rows.add(row);
                }
            }
            assertThat(ps.getMoreResults()).as("no second result set").isFalse();
            assertThat(ps.getUpdateCount()).as("no update count").isEqualTo(-1);
            return rows;
        }
    }

    private static Map<String, Object> add(Connection c, Map<String, Object> f) throws SQLException {
        Map<String, Object> args = new LinkedHashMap<>(f);
        args.put("raw_record", record(f));
        List<Map<String, Object>> rows = call(c, "core.daily_tran_online_add", args);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static Map<String, Object> lookup(Connection c, Map<String, Object> f) throws SQLException {
        Map<String, Object> args = new LinkedHashMap<>(f);
        args.remove("tran_id");
        args.remove("proc_ts");
        List<Map<String, Object>> rows = call(c, "core.daily_tran_key_lookup", args);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    void anAddInsertsOneWaitingOnlineAddAndReturnsItsKey() throws Exception {
        Map<String, Object> f = form("9000000000000001");
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            Map<String, Object> result = add(c, f);
            assertThat(result).containsOnlyKeys("daily_pk");
            c.commit();
            Map<String, Object> row = admin.sql("""
                    SELECT daily_pk, origin, cycle_id, seq, created_by, CAST(idempotency_key AS VARCHAR(36)) AS k,
                        tran_id, amount, card_num, raw_record FROM core.daily_tran WHERE created_by = :u""")
                    .param("u", USER).query().singleRow();
            assertThat(row.get("daily_pk")).isEqualTo(result.get("daily_pk"));
            assertThat(row).containsEntry("origin", "ONLINE_ADD").containsEntry("cycle_id", null)
                    .containsEntry("seq", null).containsEntry("tran_id", "9000000000000001")
                    .containsEntry("k", ((String) f.get("idempotency_key")).toUpperCase(java.util.Locale.ROOT))
                    .containsEntry("card_num", f.get("card_num")).containsEntry("raw_record", record(f));
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-12.50");
        }
    }

    @Test
    void aReportRequestInsertsOneRequestedRowAndReturnsItsIdAndTime() throws Exception {
        try (Connection c = asTransactionSvc()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("report_type", "CUSTOM");
            args.put("start_date", LocalDate.of(2022, 7, 1));
            args.put("end_date", LocalDate.of(2022, 7, 6));
            args.put("requested_by", USER);
            List<Map<String, Object>> rows = call(c, "batch.report_run_request", args);
            c.commit();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).containsOnlyKeys("report_run_id", "requested_at");
            Map<String, Object> row = admin.sql("""
                    SELECT report_run_id, requested_at, status, job_execution_id, started_at, finished_at, failure_code
                    FROM batch.report_run WHERE requested_by = :u""").param("u", USER).query().singleRow();
            assertThat(row.get("report_run_id")).isEqualTo(rows.getFirst().get("report_run_id"));
            assertThat(row.get("requested_at")).isEqualTo(rows.getFirst().get("requested_at"));
            assertThat(row).containsEntry("status", "REQUESTED").containsEntry("job_execution_id", null)
                    .containsEntry("started_at", null).containsEntry("finished_at", null)
                    .containsEntry("failure_code", null);

            args.put("start_date", LocalDate.of(2022, 7, 7));
            assertThatThrownBy(() -> call(c, "batch.report_run_request", args)).hasMessageContaining("CK_report_run_dates");
            c.rollback();
        }
    }

    @Test
    void transactionSvcCannotInsertIntoEitherTableDirectly() throws Exception {
        try (Connection c = asTransactionSvc(); Statement s = c.createStatement()) {
            for (String insert : new String[] {
                    "INSERT INTO core.daily_tran (origin, tran_id, type_cd, cat_cd, source, description, merchant_id,"
                            + " merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, raw_record)"
                            + " SELECT TOP 0 origin, tran_id, type_cd, cat_cd, source, description, merchant_id,"
                            + " merchant_name, merchant_city, merchant_zip, merchant_id, orig_ts, proc_ts, source"
                            + " FROM core.daily_tran",
                    "INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)"
                            + " VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'PROCTST1')",
                    "INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, status)"
                            + " VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'PROCTST1', 'REQUESTED')"}) {
                assertThatThrownBy(() -> s.execute(insert)).as(insert).hasMessageContaining("permission was denied");
            }
            c.rollback();
        }
    }

    @Test
    void theProceduresTakeExactlyTheirArgumentsInTheFinalTypes() {
        Map<String, List<String>> expected = Map.of(
                "batch.report_run_request", List.of("@report_type varchar(7)", "@start_date date",
                        "@end_date date", "@requested_by nchar(8)"),
                "core.daily_tran_online_add", List.of("@created_by nchar(8)", "@tran_id nchar(16)",
                        "@type_cd nchar(2)", "@cat_cd char(4)", "@source nvarchar(10)", "@description nvarchar(100)",
                        "@amount decimal(11,2)", "@merchant_id char(9)", "@merchant_name nvarchar(50)",
                        "@merchant_city nvarchar(50)", "@merchant_zip nvarchar(10)", "@card_num nchar(16)",
                        "@orig_ts nchar(26)", "@proc_ts nchar(26)", "@raw_record nchar(350)",
                        "@idempotency_key uniqueidentifier"),
                "core.daily_tran_key_lookup", List.of("@created_by nchar(8)", "@idempotency_key uniqueidentifier",
                        "@type_cd nchar(2)", "@cat_cd char(4)", "@source nvarchar(10)", "@description nvarchar(100)",
                        "@amount decimal(11,2)", "@merchant_id char(9)", "@merchant_name nvarchar(50)",
                        "@merchant_city nvarchar(50)", "@merchant_zip nvarchar(10)", "@card_num nchar(16)",
                        "@orig_ts nchar(26)"));
        for (var e : expected.entrySet()) {
            List<String> parameters = admin.sql("""
                    SELECT p.name + ' ' + t.name + CASE
                        WHEN t.name IN ('nchar', 'nvarchar') THEN '(' + CAST(p.max_length / 2 AS VARCHAR(5)) + ')'
                        WHEN t.name IN ('char', 'varchar') THEN '(' + CAST(p.max_length AS VARCHAR(5)) + ')'
                        WHEN t.name = 'decimal' THEN '(' + CAST(p.precision AS VARCHAR(3)) + ',' + CAST(p.scale AS VARCHAR(3)) + ')'
                        ELSE '' END + CASE WHEN p.is_output = 1 THEN ' output' ELSE '' END
                    FROM sys.parameters AS p JOIN sys.types AS t ON t.user_type_id = p.user_type_id
                    WHERE p.object_id = OBJECT_ID(:p) ORDER BY p.parameter_id""")
                    .param("p", e.getKey()).query(String.class).list();
            assertThat(parameters).as(e.getKey()).containsExactlyElementsOf(e.getValue());
            assertThat(parameters).as(e.getKey() + ": no argument for a protected column")
                    .noneMatch(p -> p.matches("@(status|origin|cycle_id|seq|job_execution_id|started_at|finished_at"
                            + "|failure_code|requested_at|created_at|daily_pk|report_run_id) .*"));

            Map<String, Object> module = admin.sql("""
                    SELECT COALESCE(o.principal_id, s.principal_id) AS owner_id, m.execute_as_principal_id, m.definition
                    FROM sys.objects AS o JOIN sys.schemas AS s ON s.schema_id = o.schema_id
                    JOIN sys.sql_modules AS m ON m.object_id = o.object_id WHERE o.object_id = OBJECT_ID(:p)""")
                    .param("p", e.getKey()).query().singleRow();
            assertThat(module.get("owner_id")).as(e.getKey() + " owned by dbo").isEqualTo(1);
            assertThat(module.get("execute_as_principal_id")).as(e.getKey() + " has no EXECUTE AS").isNull();
            String body = ((String) module.get("definition")).replaceAll("--[^\n]*", "")
                    .toUpperCase(java.util.Locale.ROOT);
            assertThat(body).as(e.getKey()).contains("SET NOCOUNT ON")
                    .doesNotContainPattern("BEGIN\\s+TRAN|COMMIT|ROLLBACK|SAVE\\s+TRAN|XACT_ABORT|BEGIN\\s+TRY"
                            + "|SP_EXECUTESQL|EXEC\\s*\\(|EXECUTE\\s*\\(|EXECUTE\\s+AS");
        }
    }

    @Test
    void aDuplicateReachesTheCallerAsErrorTwentySixOhOneWithItsIndexName() throws Exception {
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            Map<String, Object> first = form("9000000000000002");
            add(c, first);

            Map<String, Object> sameId = form("9000000000000002");
            assertThatThrownBy(() -> add(c, sameId)).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(2601))
                    .hasMessageContaining("UX_daily_waiting");

            Map<String, Object> sameKey = form("9000000000000003");
            sameKey.put("idempotency_key", first.get("idempotency_key"));
            assertThatThrownBy(() -> add(c, sameKey)).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(2601))
                    .hasMessageContaining("UX_daily_idempotency");
            c.rollback();
        }
    }

    @Test
    void anInvalidArgumentIsRefusedByNameWithoutItsValue() throws Exception {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        Map<String, Object> nonDigitCard = form("9000000000000004");
        nonDigitCard.put("card_num", "00001111222233X3");
        cases.put("@card_num", nonDigitCard);
        Map<String, Object> noKey = form("9000000000000004");
        noKey.put("idempotency_key", null);
        cases.put("@idempotency_key", noKey);
        Map<String, Object> noUser = form("9000000000000004");
        noUser.put("created_by", "");
        cases.put("@created_by", noUser);
        Map<String, Object> nonAscii = form("9000000000000004");
        nonAscii.put("orig_ts", pad("2022-07-0\u00e9", 26));
        cases.put("@orig_ts", nonAscii);
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            for (var e : cases.entrySet()) {
                assertThatThrownBy(() -> add(c, e.getValue())).as(e.getKey())
                        .isInstanceOfSatisfying(SQLException.class, x -> assertThat(x.getErrorCode()).isEqualTo(50417))
                        .hasMessageContaining(e.getKey()).hasMessageNotContaining("0000111122223333")
                        .hasMessageNotContaining("00001111222233X3");
            }
            Map<String, Object> f = form("9000000000000004");
            Map<String, Object> args = new LinkedHashMap<>(f);
            args.put("card_num", "0000111122224444");
            args.put("raw_record", record(f));
            assertThatThrownBy(() -> call(c, "core.daily_tran_online_add", args))
                    .isInstanceOfSatisfying(SQLException.class, x -> assertThat(x.getErrorCode()).isEqualTo(50417))
                    .hasMessageContaining("@raw_record").hasMessageNotContaining("0000111122223333")
                    .hasMessageNotContaining("0000111122224444");
            c.rollback();
        }
        assertThat(admin.sql("SELECT COUNT(*) FROM core.daily_tran WHERE created_by = :u").param("u", USER)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void anAddNeedsTheWindowLockAndNoOpenWindow() throws Exception {
        try (Connection c = asTransactionSvc()) {
            assertThatThrownBy(() -> add(c, form("9000000000000005"))).as("no transaction")
                    .isInstanceOfSatisfying(SQLException.class, x -> assertThat(x.getErrorCode()).isEqualTo(50418));
            beginTransaction(c);
            assertThatThrownBy(() -> add(c, form("9000000000000005"))).as("a transaction without the lock")
                    .isInstanceOfSatisfying(SQLException.class, x -> assertThat(x.getErrorCode()).isEqualTo(50418));
            c.rollback();
        }
        admin.sql("INSERT INTO ops.batch_window (run_key, mode, opened_by) VALUES ('carddemo_test/procedures',"
                + " 'WRITES_BLOCKED', 'proceduresTest')").update();
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            assertThatThrownBy(() -> add(c, form("9000000000000005")))
                    .isInstanceOfSatisfying(SQLException.class, x -> assertThat(x.getErrorCode()).isEqualTo(50419));
            c.rollback();
        } finally {
            admin.sql("DELETE FROM ops.batch_window WHERE opened_by = 'proceduresTest'").update();
        }
    }

    @Test
    void theLookupReplaysOnlyTheSameFormCardNumberIncluded() throws Exception {
        Map<String, Object> f = form("9000000000000006");
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            assertThat(lookup(c, f)).containsEntry("found", false).containsEntry("tran_id", null)
                    .containsEntry("same_body", null);
            add(c, f);
            assertThat(lookup(c, f)).containsEntry("found", true).containsEntry("tran_id", "9000000000000006")
                    .containsEntry("same_body", true);

            Map<String, Object> changed = new LinkedHashMap<>();
            changed.put("card_num", "0000111122224444");
            changed.put("type_cd", "02");
            changed.put("cat_cd", "0002");
            changed.put("source", "OPERATOR");
            changed.put("description", "Test purchasE");
            changed.put("amount", new BigDecimal("-12.51"));
            changed.put("merchant_id", "123456780");
            changed.put("merchant_name", "shop");
            changed.put("merchant_city", "Town2");
            changed.put("merchant_zip", "10002");
            changed.put("orig_ts", pad("2022-07-07", 26));
            for (var e : changed.entrySet()) {
                Map<String, Object> retry = new LinkedHashMap<>(f);
                retry.put(e.getKey(), e.getValue());
                assertThat(lookup(c, retry)).as(e.getKey()).containsEntry("found", true)
                        .containsEntry("same_body", false);
            }

            Map<String, Object> otherUser = new LinkedHashMap<>(f);
            otherUser.put("created_by", OTHER_USER);
            assertThat(lookup(c, otherUser)).containsEntry("found", false);
            c.rollback();
        }
    }

    @Test
    void theLookupStillReplaysOnALaterDayAndAfterTheClaim() throws Exception {
        Map<String, Object> f = form("9000000000000007");
        try (Connection c = asTransactionSvc()) {
            holdWindowLock(c);
            add(c, f);
            c.commit();
        }
        admin.sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES ('2101-01-01', '2101010100')").update();
        admin.sql("""
                UPDATE core.daily_tran SET seq = 1,
                    cycle_id = (SELECT cycle_id FROM batch.cycle WHERE business_date = '2101-01-01')
                WHERE created_by = :u""").param("u", USER).update();
        Map<String, Object> nextDay = new LinkedHashMap<>(f);
        nextDay.put("proc_ts", pad("2026-11-08", 26));
        try (Connection c = asTransactionSvc()) {
            assertThat(lookup(c, nextDay)).containsEntry("found", true).containsEntry("tran_id", "9000000000000007")
                    .containsEntry("same_body", true);
            c.rollback();
        } finally {
            admin.sql("DELETE FROM core.daily_tran WHERE created_by = :u").param("u", USER).update();
            admin.sql("DELETE FROM batch.cycle WHERE business_date = '2101-01-01'").update();
        }
    }
}
