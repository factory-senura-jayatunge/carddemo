package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The user ID family outside iam (V415): every column that stores a user ID is NCHAR(8) BIN2,
 * the objects that name one are recreated as they were, and the services' grants on them stand.
 */
class UserIdFamilySchemaTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final String BIN2 = "Latin1_General_100_BIN2";
    private static final String DATABASE_DEFAULT = "Latin1_General_100_CI_AS_SC_UTF8";
    /** Eight characters of the legacy repertoire that code page 1252 or UTF-8 CHAR(8) cannot hold. */
    private static final String LEGACY_USER = "U\u00C9\u0085\u00FF0001";

    private record UserIdColumn(String table, String column, String type, int chars, boolean bin2, boolean nullable) {
    }

    private static final List<UserIdColumn> COLUMNS = List.of(
            new UserIdColumn("core.[transaction]", "created_by", "nchar", 8, true, true),
            new UserIdColumn("core.daily_tran", "created_by", "nchar", 8, true, true),
            new UserIdColumn("batch.report_run", "requested_by", "nchar", 8, true, false),
            new UserIdColumn("ops.audit", "actor_user_id", "nchar", 8, true, false),
            new UserIdColumn("ops.audit", "subject_key", "nvarchar", 40, false, false),
            new UserIdColumn("billpay.payment", "paid_by", "nchar", 8, true, false));

    @BeforeEach
    @AfterEach
    void empty() {
        DB.admin().sql("DELETE FROM billpay.payment").update();
        DB.admin().sql("DELETE FROM ops.audit WHERE service = 'userIdFamilyTest'").update();
        DB.admin().sql("DELETE FROM batch.report_run WHERE report_type = 'CUSTOM' AND job_execution_id IS NULL"
                + " AND start_date = '1999-01-01'").update();
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
    }

    @Test
    void everyUserIdColumnIsNchar8Bin2WithItsNullabilityAndNoDefaultOrMask() {
        for (UserIdColumn c : COLUMNS) {
            Map<String, Object> row = DB.admin().sql("""
                    SELECT t.name AS type, c.max_length, c.collation_name, c.is_nullable, c.is_masked,
                           c.default_object_id
                    FROM sys.columns AS c
                    JOIN sys.types AS t ON t.user_type_id = c.user_type_id
                    WHERE c.object_id = OBJECT_ID(:table) AND c.name = :column""")
                    .param("table", c.table()).param("column", c.column()).query().singleRow();
            String what = c.table() + "." + c.column();
            assertThat(row.get("type")).as(what).isEqualTo(c.type());
            assertThat(((Number) row.get("max_length")).intValue()).as(what + " bytes").isEqualTo(2 * c.chars());
            assertThat(row.get("collation_name")).as(what).isEqualTo(c.bin2() ? BIN2 : DATABASE_DEFAULT);
            assertThat(row.get("is_nullable")).as(what + " nullable").isEqualTo(c.nullable());
            assertThat(row.get("is_masked")).as(what + " masked").isEqualTo(false);
            assertThat(((Number) row.get("default_object_id")).intValue()).as(what + " default").isZero();
        }
        Map<String, Object> view = DB.admin().sql("""
                SELECT t.name AS type, c.max_length, c.collation_name
                FROM sys.columns AS c JOIN sys.types AS t ON t.user_type_id = c.user_type_id
                WHERE c.object_id = OBJECT_ID('core.v_daily_reject') AND c.name = 'created_by'""").query().singleRow();
        assertThat(view).as("core.v_daily_reject.created_by").containsEntry("type", "nchar")
                .containsEntry("collation_name", BIN2);
        assertThat(((Number) view.get("max_length")).intValue()).isEqualTo(16);
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('core.v_daily_reject')"
                + " AND name = 'reason_cds'").query(Integer.class).single()).as("the view as V413 left it").isEqualTo(1);
    }

    /** The key columns of an index in order, with DESC marked, and its filter and uniqueness. */
    private static Map<String, Object> index(String table, String name) {
        return DB.admin().sql("""
                SELECT i.is_unique, i.has_filter, i.filter_definition,
                       STRING_AGG(c.name + CASE WHEN ic.is_descending_key = 1 THEN ' DESC' ELSE '' END, ', ')
                           WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.indexes AS i
                JOIN sys.index_columns AS ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns AS c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                WHERE i.object_id = OBJECT_ID(:table) AND i.name = :name AND ic.key_ordinal > 0
                GROUP BY i.is_unique, i.has_filter, i.filter_definition""")
                .param("table", table).param("name", name).query().singleRow();
    }

    @Test
    void theIndexesAndTheCheckThatNameAUserIdAreRecreatedAsTheyWere() {
        assertThat(index("core.daily_tran", "UX_daily_idempotency")).containsEntry("is_unique", true)
                .containsEntry("columns", "created_by, idempotency_key")
                .containsEntry("filter_definition", "([idempotency_key] IS NOT NULL)");
        assertThat(index("batch.report_run", "IX_report_run_requester")).containsEntry("is_unique", false)
                .containsEntry("has_filter", false).containsEntry("columns", "requested_by, requested_at DESC");
        assertThat(index("ops.audit", "IX_audit_subject")).containsEntry("is_unique", false)
                .containsEntry("has_filter", false).containsEntry("columns", "subject_type, subject_key, occurred_at");
        Map<String, Object> check = DB.admin().sql("""
                SELECT definition, is_disabled, is_not_trusted FROM sys.check_constraints
                WHERE parent_object_id = OBJECT_ID('core.daily_tran') AND name = 'CK_daily_tran_idempotency'""")
                .query().singleRow();
        assertThat(check).containsEntry("is_disabled", false).containsEntry("is_not_trusted", false);
        assertThat((String) check.get("definition")).contains("[idempotency_key] IS NULL")
                .contains("[origin]='ONLINE_ADD'").contains("[created_by] IS NOT NULL");
    }

    private static void waitingAdd(String user, String tranId, UUID key) {
        DB.admin().sql("""
                INSERT INTO core.daily_tran (origin, created_by, idempotency_key, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts,
                    proc_ts, raw_record)
                VALUES ('ONLINE_ADD', :u, CAST(:k AS UNIQUEIDENTIFIER), :id, '01', '0001', 'POS TERM', 'TEST', 1.00,
                    '000000001', 'M', 'C', 'Z', '4111111111111111', '', '', :id)""")
                .param("u", user).param("k", key == null ? null : key.toString()).param("id", tranId).update();
    }

    @Test
    void aUserIdOfTheLegacyRepertoireIsStoredUnchangedInEveryColumn() {
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card("4111111111111111", "00000000001", "000000001")
                .transaction("0000000000000001", "4111111111111111", "1.00");
        long tranPk = DB.admin().sql("SELECT tran_pk FROM core.[transaction]").query(Long.class).single();
        DB.admin().sql("UPDATE core.[transaction] SET created_by = :u").param("u", LEGACY_USER).update();
        waitingAdd(LEGACY_USER, "9000000000000001", UUID.randomUUID());
        DB.admin().sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)
                VALUES ('CUSTOM', '1999-01-01', '1999-01-31', :u)""").param("u", LEGACY_USER).update();
        DB.admin().sql("""
                INSERT INTO ops.audit (service, actor_user_id, action, subject_type, subject_key)
                VALUES ('userIdFamilyTest', :u, 'UPDATE', 'USER', :u)""").param("u", LEGACY_USER).update();
        DB.admin().sql("""
                INSERT INTO billpay.payment (tran_pk, tran_id, acct_id, paid_by, idempotency_key, amount,
                    balance_before, created_at)
                VALUES (:pk, '0000000000000001', '00000000001', :u, NEWID(), 1.00, 1.00, SYSUTCDATETIME())""")
                .param("pk", tranPk).param("u", LEGACY_USER).update();

        for (String select : new String[] {"SELECT created_by FROM core.[transaction]",
                "SELECT created_by FROM core.daily_tran",
                "SELECT requested_by FROM batch.report_run WHERE start_date = '1999-01-01'",
                "SELECT actor_user_id FROM ops.audit WHERE service = 'userIdFamilyTest'",
                "SELECT subject_key FROM ops.audit WHERE service = 'userIdFamilyTest'",
                "SELECT paid_by FROM billpay.payment"}) {
            assertThat(DB.admin().sql(select).query(String.class).single()).as(select).isEqualTo(LEGACY_USER);
        }
        assertThatThrownBy(() -> DB.admin().sql("UPDATE core.[transaction] SET created_by = :u")
                .param("u", LEGACY_USER + "X").update()).as("nine characters").hasMessageContaining("truncated");
    }

    @Test
    void anIdempotencyKeyStaysUniquePerUserComparedByCodePoint() {
        UUID key = UUID.randomUUID();
        waitingAdd(LEGACY_USER, "9000000000000001", key);
        assertThatThrownBy(() -> waitingAdd(LEGACY_USER, "9000000000000002", key))
                .hasMessageContaining("UX_daily_idempotency");
        // The same letters with U+00E9 instead of U+00C9: another user under BIN2.
        waitingAdd(LEGACY_USER.replace('\u00C9', '\u00E9'), "9000000000000003", key);
        assertThatThrownBy(() -> DB.admin().sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, idempotency_key, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts,
                    proc_ts, raw_record)
                SELECT NULL, NULL, 'ONLINE_ADD', NEWID(), '9000000000000004', '01', '0001', 'POS TERM', 'TEST', 1.00,
                    '000000001', 'M', 'C', 'Z', '4111111111111111', '', '', '9000000000000004'""").update())
                .hasMessageContaining("CK_daily_tran_idempotency");
    }

    private static boolean has(ServiceLogin login, String table, String permission, String column) {
        JdbcClient client = JdbcClient.create(DB.dataSource(login));
        String sql = column == null
                ? "SELECT HAS_PERMS_BY_NAME(:t, 'OBJECT', :p)"
                : "SELECT HAS_PERMS_BY_NAME(:t, 'OBJECT', :p, :c, 'COLUMN')";
        var statement = client.sql(sql).param("t", table).param("p", permission);
        if (column != null) {
            statement = statement.param("c", column);
        }
        return statement.query(Integer.class).single() == 1;
    }

    /** The grants of the R__grants files on the altered columns, applied again after V415. */
    @Test
    void theServicesKeepTheirGrantsOnTheAlteredColumns() {
        assertThat(has(ServiceLogin.BATCH, "core.[transaction]", "SELECT", "created_by")).isTrue();
        assertThat(has(ServiceLogin.BATCH, "core.[transaction]", "INSERT", null)).isTrue();
        assertThat(has(ServiceLogin.BATCH, "core.daily_tran", "SELECT", "created_by")).isTrue();
        assertThat(has(ServiceLogin.BATCH, "core.daily_tran", "UPDATE", "created_by")).as("only cycle_id, seq").isFalse();
        assertThat(has(ServiceLogin.BATCH, "core.v_daily_reject", "SELECT", "created_by")).isTrue();
        assertThat(has(ServiceLogin.BATCH, "batch.report_run", "SELECT", "requested_by")).isTrue();
        assertThat(has(ServiceLogin.BILLPAY, "core.[transaction]", "SELECT", "created_by")).isTrue();
        assertThat(has(ServiceLogin.BILLPAY, "core.[transaction]", "INSERT", null)).isTrue();
        assertThat(has(ServiceLogin.BILLPAY, "core.daily_tran", "SELECT", "created_by")).isFalse();
        assertThat(has(ServiceLogin.BILLPAY, "billpay.payment", "INSERT", null)).isTrue();
        assertThat(has(ServiceLogin.BILLPAY, "billpay.payment", "SELECT", "paid_by")).isTrue();
        assertThat(has(ServiceLogin.BILLPAY, "billpay.payment", "UPDATE", "paid_by")).isFalse();
        assertThat(has(ServiceLogin.TRANSACTION, "core.[transaction]", "SELECT", "created_by")).isFalse();
        assertThat(has(ServiceLogin.TRANSACTION, "core.[transaction]", "SELECT", "tran_id")).isTrue();
        assertThat(has(ServiceLogin.AUTHORIZATION, "ops.audit", "INSERT", null)).isTrue();
        assertThat(has(ServiceLogin.AUTHORIZATION, "ops.audit", "SELECT", "actor_user_id")).isFalse();
    }

    @Test
    void theRejectViewStillJoinsTheUserOfAnOnlineAdd() {
        DB.admin().sql("INSERT INTO batch.cycle (business_date, interest_parm) VALUES ('2022-07-06', '2022070600')")
                .update();
        int cycle = DB.admin().sql("SELECT cycle_id FROM batch.cycle WHERE business_date = :d")
                .param("d", LocalDate.of(2022, 7, 6)).query(Integer.class).single();
        waitingAdd(LEGACY_USER, "9000000000000001", null);
        long pk = DB.admin().sql("SELECT daily_pk FROM core.daily_tran").query(Long.class).single();
        DB.admin().sql("UPDATE core.daily_tran SET cycle_id = :c, seq = 1").param("c", cycle).update();
        DB.admin().sql("""
                INSERT INTO core.daily_reject (cycle_id, seq, daily_pk, reason_cd, reason_desc, reason_cds, raw_record)
                VALUES (:c, 1, :pk, '0100', 'INVALID CARD NUMBER FOUND', '0100', 'X')""")
                .param("c", cycle).param("pk", pk).update();
        try {
            assertThat(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH))
                    .sql("SELECT created_by FROM core.v_daily_reject").query(String.class).single())
                    .isEqualTo(LEGACY_USER);
        } finally {
            DB.admin().sql("DELETE FROM core.daily_reject").update();
            DB.admin().sql("DELETE FROM core.daily_tran").update();
            DB.admin().sql("DELETE FROM batch.cycle WHERE cycle_id = :c").param("c", cycle).update();
        }
    }
}
