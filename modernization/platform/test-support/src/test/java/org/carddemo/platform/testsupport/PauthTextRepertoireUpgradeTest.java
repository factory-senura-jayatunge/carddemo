package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
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
 * V505 and V508 on a database that already holds authorizations: a scratch database migrated up to V504 gets a
 * detail, its fraud copy, a processed message and an outbox reply, then the remaining migrations.
 */
class PauthTextRepertoireUpgradeTest {

    private static final String ACCT = "00000000001";
    private static final String CARD = "4111111111111111";
    private static final String REPLY = "4111111111111111" + "000000000000001" + "000001" + "00" + "0000" + "12.34"
            + " ".repeat(15);
    private static final String DETAIL = """
            SELECT orig_date, orig_time, auth_type, card_expiry_date, message_type, message_source, processing_code,
                   merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                   merchant_city, merchant_state, merchant_zip, transaction_id, fraud_changed_by, auth_id_code,
                   auth_resp_reason
            FROM pauth.auth_detail""";
    private static final String FRAUD = """
            SELECT auth_type, card_expiry_date, message_type, message_source, processing_code, merchant_category_code,
                   acqr_country_code, merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip,
                   transaction_id, reported_by, auth_id_code, auth_resp_reason
            FROM pauth.auth_fraud""";

    @Test
    void storedAuthorizationsKeepTheirValuesMasksAndGrantsThroughTheConversion() throws Exception {
        DbSettings shared = TestDatabase.shared().settings();
        String name = shared.databaseName() + "_v505";
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
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_detail", "merchant_name"))
                    .isEqualTo("varchar(22) not null");

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
                        'P', 'F', '2022-07-06', 'ADMIN001')""")
                    .param("acct", ACCT).param("card", CARD).update();
            jdbc.sql("""
                    INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_type, card_expiry_date, message_type,
                        message_source, auth_id_code, auth_resp_code, auth_resp_reason, processing_code,
                        transaction_amt, approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode,
                        merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id,
                        match_status, auth_fraud, fraud_rpt_date, acct_id, cust_id, reported_by)
                    SELECT auth_id, card_num, processed_at, auth_type, card_expiry_date, message_type, message_source,
                        auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                        merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                        merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud,
                        fraud_rpt_date, acct_id, '000000001', fraud_changed_by
                    FROM pauth.auth_detail""").update();
            jdbc.sql("""
                    INSERT INTO pauth.processed_message (msg_key, key_kind, auth_id, reply_body)
                    SELECT HASHBYTES('SHA2_256', 'M|upgrade'), 'M', auth_id, :reply FROM pauth.auth_detail""")
                    .param("reply", REPLY).update();
            jdbc.sql("""
                    INSERT INTO pauth.outbox (exchange_name, routing_key, content_type, expiration_ms, body)
                    VALUES ('carddemo.auth', 'reply', 'text/plain', 1000, :reply)""").param("reply", REPLY).update();
            // Statistics on the columns, as queries on a live database create them.
            jdbc.sql("""
                    SELECT COUNT(*) FROM pauth.auth_detail
                    WHERE merchant_name > '' AND merchant_city > '' AND merchant_zip > '' AND merchant_id > ''
                      AND transaction_id > '' AND orig_date > '' AND pos_entry_mode > '' AND auth_id_code > ''
                      AND auth_resp_reason > ''""").query(Integer.class).single();
            jdbc.sql("""
                    SELECT COUNT(*) FROM pauth.auth_fraud
                    WHERE merchant_city > '' AND merchant_name > '' AND auth_id_code > '' AND auth_resp_reason > ''""")
                    .query(Integer.class).single();
            jdbc.sql("SELECT COUNT(*) FROM pauth.outbox WHERE body > ''").query(Integer.class).single();
            Map<String, Object> detail = jdbc.sql(DETAIL).query().singleRow();
            Map<String, Object> fraud = jdbc.sql(FRAUD).query().singleRow();

            CarddemoMigrations.migrate(admin);

            assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version IN ('505', '508')")
                    .query(Boolean.class).list()).containsExactly(true, true);
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_detail", "merchant_name"))
                    .isEqualTo("nvarchar(44) not null");
            assertThat(PauthSchemaTest.column(jdbc, "pauth.auth_fraud", "auth_id_code")).isEqualTo("nchar(12) null");
            assertThat(jdbc.sql(DETAIL).query().singleRow()).isEqualTo(detail);
            assertThat(jdbc.sql(FRAUD).query().singleRow()).isEqualTo(fraud);
            assertThat(detail).containsEntry("merchant_name", "CORNER GROCERY STORE12")
                    .containsEntry("message_source", "POS   ").containsEntry("fraud_changed_by", "ADMIN001")
                    .containsEntry("auth_id_code", "000001").containsEntry("auth_resp_reason", "0000");

            JdbcClient authorization = JdbcClient.create(provisioner.service(ServiceLogin.AUTHORIZATION));
            assertThat(authorization.sql("SELECT reply_body FROM pauth.processed_message").query(String.class).single())
                    .isEqualTo(REPLY);
            assertThat(authorization.sql("SELECT body FROM pauth.outbox").query(String.class).single())
                    .isEqualTo(REPLY);
            assertThat(authorization.sql("SELECT card_num FROM pauth.auth_detail").query(String.class).single())
                    .isEqualTo(CARD);
            assertThat(authorization.sql("UPDATE pauth.outbox SET attempts = 1, sent_at = SYSUTCDATETIME()").update())
                    .isEqualTo(1);
        } finally {
            drop(provisioner, settings);
        }
    }

    private static void drop(DatabaseProvisioner provisioner, DbSettings settings) throws Exception {
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
