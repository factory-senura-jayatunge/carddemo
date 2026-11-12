package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.carddemo.db.ServiceLogin;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V509 on a database that already holds fraud rows: a scratch database migrated up to V508 gets a detail, fraud
 * copies and statistics on the three columns, then the remaining migrations.
 */
class PauthFraudCollationUpgradeTest {

    private static final String ACCT = "00000000001";
    private static final String CARD = "4111111111111111";
    private static final String BIN2 = "Latin1_General_100_BIN2";
    private static final String FRAUD = """
            SELECT auth_id, auth_resp_code, auth_resp_reason, match_status FROM pauth.auth_fraud ORDER BY auth_id""";
    /** A comparison across the two tables, as a fraud report would join them; 468 under two collations. */
    private static final String CROSS = """
            SELECT COUNT(*) FROM pauth.auth_fraud f JOIN pauth.auth_detail d
              ON d.auth_resp_code = f.auth_resp_code AND d.auth_resp_reason = f.auth_resp_reason
             AND d.match_status = f.match_status""";

    @Test
    void storedFraudRowsKeepTheirValuesAndGetTheDetailCollation() throws Exception {
        try (Scratch scratch = new Scratch("_v509")) {
            JdbcClient jdbc = scratch.migratedTo("508");
            String database = jdbc.sql("SELECT CONVERT(SYSNAME, DATABASEPROPERTYEX(DB_NAME(), 'Collation'))")
                    .query(String.class).single();
            assertThat(collations(jdbc)).containsOnly(database);

            scratch.detail(jdbc);
            jdbc.sql("""
                    INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_resp_code, auth_resp_reason,
                        match_status, auth_fraud, fraud_rpt_date, acct_id, reported_by)
                    SELECT auth_id, card_num, processed_at, auth_resp_code, auth_resp_reason, match_status, 'F',
                        '2022-07-06', acct_id, N'ADMIN001'
                    FROM pauth.auth_detail""").update();
            fraud(jdbc, 900001, "05", "\u0000\u00ffa\u00e9", "M");
            fraud(jdbc, 900002, "a\u0000", "AAAA", "r");
            // One character from U+0080 up fills both bytes of the UTF-8 CHAR(2).
            fraud(jdbc, 900003, "\u00e9", "\u00ff\u00ff\u00ff\u00ff", "\u0000");
            jdbc.sql("""
                    INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_fraud, fraud_rpt_date, acct_id,
                        reported_by)
                    VALUES (900004, :card, '2022-07-06T10:00:00', 'F', '2022-07-06', :acct, N'ADMIN001')""")
                    .param("card", CARD).param("acct", ACCT).update();
            // Statistics on the columns, created by queries as on a live database and by hand.
            jdbc.sql("SELECT COUNT(*) FROM pauth.auth_fraud WHERE match_status = 'M' AND auth_resp_code > ''"
                    + " AND auth_resp_reason > N''").query(Integer.class).single();
            jdbc.sql("CREATE STATISTICS st_v509_probe ON pauth.auth_fraud (match_status, auth_resp_code)").update();
            jdbc.sql("CREATE STATISTICS st_v509_reason ON pauth.auth_fraud (auth_resp_reason)").update();
            assertThatThrownBy(() -> jdbc.sql(CROSS).query(Integer.class).single())
                    .hasMessageContaining("collation conflict");
            List<Map<String, Object>> before = jdbc.sql(FRAUD).query().listOfRows();

            CarddemoMigrations.migrate(scratch.admin());

            assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '509'")
                    .query(Boolean.class).list()).containsExactly(true);
            assertThat(collations(jdbc)).containsOnly(BIN2);
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_fraud", "auth_resp_code")).isEqualTo("char(2) null");
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_fraud", "auth_resp_reason")).isEqualTo("nchar(8) null");
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_fraud", "match_status")).isEqualTo("char(1) null");

            List<Map<String, Object>> after = jdbc.sql(FRAUD).query().listOfRows();
            assertThat(after).hasSameSizeAs(before);
            for (int i = 0; i < before.size(); i++) {
                Map<String, Object> was = before.get(i);
                Map<String, Object> now = after.get(i);
                assertThat(now.get("auth_id")).isEqualTo(was.get("auth_id"));
                assertThat(now.get("auth_resp_reason")).as("auth_resp_reason of " + was.get("auth_id"))
                        .isEqualTo(was.get("auth_resp_reason"));
                assertThat(now.get("match_status")).as("match_status of " + was.get("auth_id"))
                        .isEqualTo(was.get("match_status"));
                // CHAR pads to its width in characters; under UTF-8 the width counted bytes.
                assertThat(now.get("auth_resp_code")).as("auth_resp_code of " + was.get("auth_id"))
                        .isEqualTo(was.get("auth_resp_code") == null ? null
                                : pad((String) was.get("auth_resp_code"), 2));
            }
            assertThat(after.get(1)).containsEntry("auth_resp_code", "05").containsEntry("auth_resp_reason", "\u0000\u00ffa\u00e9");
            assertThat(after.get(2)).containsEntry("auth_resp_code", "a\u0000").containsEntry("match_status", "r");
            assertThat(after.get(3)).containsEntry("auth_resp_code", "\u00e9 ").containsEntry("match_status", "\u0000");
            assertThat(Arrays.asList(after.get(4).get("auth_resp_code"), after.get(4).get("auth_resp_reason"),
                    after.get(4).get("match_status"))).containsOnlyNulls();

            // The copies now compare with their detail, case and U+0000 included.
            assertThat(jdbc.sql(CROSS).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM pauth.auth_fraud WHERE match_status = 'R'")
                    .query(Integer.class).single()).isZero();

            JdbcClient authorization = JdbcClient.create(scratch.service(ServiceLogin.AUTHORIZATION));
            assertThat(authorization.sql("UPDATE pauth.auth_fraud SET match_status = 'M' WHERE auth_id = 900002")
                    .update()).isEqualTo(1);
        }
    }

    @Test
    void aValueThatCodePage1252CannotHoldStopsTheMigrationBeforeAnyChange() throws Exception {
        try (Scratch scratch = new Scratch("_v509f")) {
            JdbcClient jdbc = scratch.migratedTo("508");
            String database = jdbc.sql("SELECT CONVERT(SYSNAME, DATABASEPROPERTYEX(DB_NAME(), 'Collation'))")
                    .query(String.class).single();
            // U+0100 has two UTF-8 bytes and no 1252 byte.
            fraud(jdbc, 900001, "\u0100", "0000", "P");
            jdbc.sql("CREATE STATISTICS st_v509_probe ON pauth.auth_fraud (match_status)").update();

            assertThatThrownBy(() -> CarddemoMigrations.migrate(scratch.admin()))
                    .hasStackTraceContaining("V509: a stored pauth.auth_fraud auth_resp_code or match_status");

            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '509' AND success = 1")
                    .query(Integer.class).single()).isZero();
            assertThat(collations(jdbc)).containsOnly(database);
            assertThat(jdbc.sql("SELECT auth_resp_code FROM pauth.auth_fraud").query(String.class).single())
                    .isEqualTo("\u0100");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM sys.stats WHERE object_id = OBJECT_ID('pauth.auth_fraud')"
                    + " AND name = 'st_v509_probe'").query(Integer.class).single()).isEqualTo(1);
        }
    }

    private static void fraud(JdbcClient jdbc, long authId, String code, String reason, String status) {
        jdbc.sql("""
                INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_resp_code, auth_resp_reason,
                    match_status, auth_fraud, fraud_rpt_date, acct_id, reported_by)
                VALUES (:id, :card, '2022-07-06T10:00:00', :code, :reason, :status, 'F', '2022-07-06', :acct,
                    N'ADMIN001')""")
                .param("id", authId).param("card", CARD).param("code", code).param("reason", reason)
                .param("status", status).param("acct", ACCT).update();
    }

    private static List<String> collations(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT collation_name FROM sys.columns
                WHERE object_id = OBJECT_ID('pauth.auth_fraud')
                  AND name IN ('auth_resp_code', 'auth_resp_reason', 'match_status')""").query(String.class).list();
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /** A scratch database with its own logins, dropped on close. */
    private static final class Scratch implements AutoCloseable {

        private final DbSettings settings;
        private final DatabaseProvisioner provisioner;

        Scratch(String suffix) throws Exception {
            DbSettings shared = TestDatabase.shared().settings();
            String name = shared.databaseName() + suffix;
            settings = new DbSettings(shared.serverUrl(), shared.adminUser(), shared.adminPassword(), name, name + "_",
                    shared.passwords());
            provisioner = new DatabaseProvisioner(settings);
            provisioner.recreateDatabase();
            provisioner.provisionLogins();
        }

        DataSource admin() {
            return provisioner.databaseAdmin();
        }

        DataSource service(ServiceLogin login) {
            return provisioner.service(login);
        }

        JdbcClient migratedTo(String version) {
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin()).getConfiguration()).target(version)
                    .load().migrate();
            return JdbcClient.create(admin());
        }

        /** One approved detail with the codes the service sets, for the comparison across the tables. */
        void detail(JdbcClient jdbc) {
            new CoreFixtures(jdbc).account(CoreFixtures.Account.of(ACCT, "0.00"))
                    .customer("000000001")
                    .card(CARD, ACCT, "000000001");
            jdbc.sql("""
                    INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                        cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                    VALUES (:acct, '000000001', 1000.00, 500.00, 12.34, 0, 1, 0, 12.34, 0)""")
                    .param("acct", ACCT).update();
            jdbc.sql("""
                    INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                        orig_time, card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                        auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                        merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                        merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud,
                        fraud_rpt_date, fraud_changed_by)
                    VALUES (:acct, '2022-07-06T10:00:00', 77814, 899999999, '220706', '100000', :card, '0100', '0925',
                        'AUTHRQ', 'POS   ', '000001', '00', '0000', '000000', 12.34, 12.34, '5411', '840', '01',
                        '000000000000001', 'CORNER GROCERY STORE12', 'SPRINGFIELD', 'TX', '75001', '000000000000001',
                        'P', 'F', '2022-07-06', N'ADMIN001')""")
                    .param("acct", ACCT).param("card", CARD).update();
        }

        @Override
        public void close() throws Exception {
            try (Connection c = provisioner.serverAdmin().getConnection(); Statement s = c.createStatement()) {
                String db = "[" + settings.databaseName() + "]";
                s.execute("IF DB_ID(N'" + settings.databaseName() + "') IS NOT NULL BEGIN ALTER DATABASE " + db
                        + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE " + db + "; END");
                for (ServiceLogin login : ServiceLogin.values()) {
                    String loginName = settings.loginName(login);
                    s.execute("IF SUSER_ID(N'" + loginName + "') IS NOT NULL DROP LOGIN [" + loginName + "]");
                }
            }
        }
    }
}
