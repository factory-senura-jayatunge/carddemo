package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.carddemo.db.ServiceLogin;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V504 on a database that already holds summaries: a scratch database migrated up to V503 gets
 * summaries at the edges of the old domain, then the remaining migrations.
 */
class PauthSummaryUpgradeTest {

    private static final String ACCT = "00000000001";
    private static final String SUMMARY = """
            SELECT credit_limit, cash_limit, credit_balance, cash_balance, approved_auth_cnt, declined_auth_cnt,
                   approved_auth_amt, declined_auth_amt
            FROM pauth.auth_summary WHERE acct_id = :acct""";

    @Test
    void existingSummariesKeepTheirValuesAndGrantsThroughTheWidening() throws Exception {
        DbSettings shared = TestDatabase.shared().settings();
        String name = shared.databaseName() + "_v504";
        DbSettings settings = new DbSettings(shared.serverUrl(), shared.adminUser(), shared.adminPassword(), name,
                name + "_", shared.passwords());
        DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
        provisioner.recreateDatabase();
        provisioner.provisionLogins();
        try {
            DataSource admin = provisioner.databaseAdmin();
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin).getConfiguration()).target("503").load()
                    .migrate();
            JdbcClient jdbc = JdbcClient.create(admin);
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_summary", "credit_limit"))
                    .isEqualTo("decimal(11,2) not null");

            new CoreFixtures(jdbc).account(CoreFixtures.Account.of(ACCT, "0.00"))
                    .customer("000000001")
                    .card("4111111111111111", ACCT, "000000001");
            jdbc.sql("""
                    INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                        cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                    VALUES (:acct, '000000001', 999999999.99, 0.01, 999999999.99, -999999999.99, 7, 40000,
                        123.45, 9999999999999.99)""").param("acct", ACCT).update();
            // Statistics on the columns, as queries on a live database create them.
            jdbc.sql("""
                    SELECT COUNT(*) FROM pauth.auth_summary
                    WHERE credit_limit > 0 AND cash_limit > 0 AND credit_balance > 0 AND cash_balance < 0""")
                    .query(Integer.class).single();
            List<Object> before = summary(jdbc);

            CarddemoMigrations.migrate(admin);

            assertThat(summary(jdbc)).isEqualTo(before);
            assertThat(before).startsWith(new BigDecimal("999999999.99"), new BigDecimal("0.01"),
                    new BigDecimal("999999999.99"), new BigDecimal("-999999999.99"));
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_summary", "credit_limit"))
                    .isEqualTo("decimal(12,2) not null");
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_summary", "cash_balance"))
                    .isEqualTo("decimal(15,2) not null");
            assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '504'")
                    .query(Boolean.class).single()).isTrue();

            JdbcClient authorization = JdbcClient.create(provisioner.service(ServiceLogin.AUTHORIZATION));
            assertThat(authorization.sql("""
                            UPDATE pauth.auth_summary SET credit_limit = 9999999999.99, credit_balance = 9999999999999.99
                            WHERE acct_id = :acct""").param("acct", ACCT).update()).isEqualTo(1);
        } finally {
            drop(provisioner, settings);
        }
    }

    private static List<Object> summary(JdbcClient jdbc) {
        return jdbc.sql(SUMMARY).param("acct", ACCT).query((rs, n) -> {
            List<Object> row = new java.util.ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                row.add(rs.getObject(i));
            }
            return row;
        }).single();
    }

    static void drop(DatabaseProvisioner provisioner, DbSettings settings) throws Exception {
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
