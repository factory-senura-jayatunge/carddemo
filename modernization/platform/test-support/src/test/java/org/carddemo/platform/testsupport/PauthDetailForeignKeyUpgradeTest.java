package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V507 on a database that already holds details: a scratch database migrated up to V504 gets a
 * summary with two details, then the remaining migrations.
 */
class PauthDetailForeignKeyUpgradeTest {

    private static final String ACCT = "00000000001";
    private static final String FK = """
            SELECT CONCAT(f.delete_referential_action_desc, '|', f.is_disabled, '|', f.is_not_trusted)
            FROM sys.foreign_keys f
            WHERE f.parent_object_id = OBJECT_ID('pauth.auth_detail') AND f.name = 'FK_auth_detail_summary'""";
    private static final String ROWS = """
            SELECT CONCAT(auth_id, '|', acct_id, '|', auth_time_9c, '|', CONVERT(VARCHAR(20), CAST(row_version AS BIGINT)))
            FROM pauth.auth_detail ORDER BY auth_id""";

    @Test
    void existingDetailsKeepTheirRowsAndTheKeyLosesItsCascade() throws Exception {
        DbSettings shared = TestDatabase.shared().settings();
        String name = shared.databaseName() + "_v507";
        DbSettings settings = new DbSettings(shared.serverUrl(), shared.adminUser(), shared.adminPassword(), name,
                name + "_", shared.passwords());
        DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
        provisioner.recreateDatabase();
        provisioner.provisionLogins();
        try {
            DataSource admin = provisioner.databaseAdmin();
            Flyway.configure().configuration(CarddemoMigrations.flyway(admin).getConfiguration()).target("504").load()
                    .migrate();
            JdbcClient jdbc = JdbcClient.create(admin);
            assertThat(jdbc.sql(FK).query(String.class).single()).isEqualTo("CASCADE|0|0");

            new CoreFixtures(jdbc).account(CoreFixtures.Account.of(ACCT, "0.00"))
                    .customer("000000001")
                    .card("4111111111111111", ACCT, "000000001");
            jdbc.sql("""
                    INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                        cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                    VALUES (:acct, '000000001', 1000.00, 500.00, 24.68, 0, 2, 0, 24.68, 0)""").param("acct", ACCT)
                    .update();
            for (int time9c : new int[] {899999999, 899999998}) {
                jdbc.sql("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source,
                            auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                            approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                            merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        VALUES (:acct, '2022-07-06T10:00:00', 77814, :time9c, '220706', '100000', '4111111111111111',
                            '0100', '0925', 'AUTHRQ', 'POS   ', '000001', '00', '0000', '000000', 12.34, 12.34, '5411',
                            '840', '01', '000000000000001', 'MERCHANT', 'CITY', 'TX', '75001', '000000000000001', 'P')""")
                        .param("acct", ACCT).param("time9c", time9c).update();
            }
            List<String> before = jdbc.sql(ROWS).query(String.class).list();

            CarddemoMigrations.migrate(admin);

            assertThat(jdbc.sql(ROWS).query(String.class).list()).as("no detail is rewritten").isEqualTo(before)
                    .hasSize(2);
            assertThat(jdbc.sql(FK).query(String.class).single()).isEqualTo("NO_ACTION|0|0");
            assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '507'")
                    .query(Boolean.class).single()).isTrue();
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM pauth.auth_summary").update())
                    .rootCause().isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(547));
            assertThat(jdbc.sql(ROWS).query(String.class).list()).isEqualTo(before);
        } finally {
            PauthSummaryUpgradeTest.drop(provisioner, settings);
        }
    }
}
