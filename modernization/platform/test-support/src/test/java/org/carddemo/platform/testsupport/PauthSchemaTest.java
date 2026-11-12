package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class PauthSchemaTest {

    private static final String ACCT = "00000000001";
    private static final String CARD = "4111111111111111";

    private final TestDatabase db = TestDatabase.shared();

    @BeforeEach
    void accountWithACard() {
        db.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00"))
                .customer("000000001")
                .card(CARD, ACCT, "000000001");
    }

    @Test
    void aSummaryNeedsItsAccount() {
        assertThatThrownBy(() -> summary("00000000002")).hasMessageContaining("FK_auth_summary_account");
    }

    @Test
    void runningTotalsHoldMoreThanTheImsDomain() {
        summary(ACCT);
        db.admin().sql("""
                UPDATE pauth.auth_summary SET declined_auth_cnt = 40000, declined_auth_amt = 9999999999999.99
                WHERE acct_id = :acct""").param("acct", ACCT).update();
        assertThat(db.admin().sql("SELECT declined_auth_amt FROM pauth.auth_summary").query(BigDecimal.class).single())
                .isEqualByComparingTo("9999999999999.99");
    }

    @Test
    void summaryLimitsTakeTheAccountDomainAndPendingBalancesTheRunningTotals() {
        assertThat(column(db.admin(), "pauth.auth_summary", "credit_limit")).isEqualTo("decimal(12,2) not null");
        assertThat(column(db.admin(), "pauth.auth_summary", "cash_limit")).isEqualTo("decimal(12,2) not null");
        assertThat(column(db.admin(), "pauth.auth_summary", "credit_balance")).isEqualTo("decimal(15,2) not null");
        assertThat(column(db.admin(), "pauth.auth_summary", "cash_balance")).isEqualTo("decimal(15,2) not null");
        assertThat(column(db.admin(), "core.account", "credit_limit")).isEqualTo("decimal(12,2) not null");
        assertThat(column(db.admin(), "core.account", "cash_credit_limit")).isEqualTo("decimal(12,2) not null");
        // The fraud screen records who changed the flag and when it was reported (DEF-AUDT-006).
        // max_length counts bytes: NCHAR(8) since V505.
        assertThat(column(db.admin(), "pauth.auth_detail", "fraud_changed_by")).isEqualTo("nchar(16) null");
        assertThat(column(db.admin(), "pauth.auth_fraud", "reported_at")).isEqualTo("datetime2(3) not null");
    }

    @Test
    void theLargestAccountLimitsAndPendingBalancesFitTheSummary() {
        db.admin().sql("UPDATE core.account SET credit_limit = 9999999999.99, cash_credit_limit = 9999999999.99")
                .update();
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        authorization.sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                SELECT acct_id, '000000001', credit_limit, cash_credit_limit, 9999999999999.99, -9999999999999.99,
                    0, 0, 0, 0
                FROM core.account WHERE acct_id = :acct""").param("acct", ACCT).update();
        assertThat(db.admin().sql("""
                        SELECT credit_limit, cash_limit, credit_balance, cash_balance FROM pauth.auth_summary""")
                .query((rs, n) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4))).single())
                .containsExactly(new BigDecimal("9999999999.99"), new BigDecimal("9999999999.99"),
                        new BigDecimal("9999999999999.99"), new BigDecimal("-9999999999999.99"));

        assertThatThrownBy(() -> authorization.sql("UPDATE pauth.auth_summary SET credit_limit = 10000000000.00")
                .update()).hasMessageContaining("Arithmetic overflow");
        assertThatThrownBy(() -> authorization.sql("UPDATE pauth.auth_summary SET credit_balance = credit_balance + 0.01")
                .update()).hasMessageContaining("Arithmetic overflow");
    }

    @Test
    void twoDetailsOfOneAccountCannotShareTheNineComplementKey() {
        summary(ACCT);
        detail(ACCT, 77814, 899999999);
        assertThatThrownBy(() -> detail(ACCT, 77814, 899999999)).hasMessageContaining("UQ_auth_detail_9c");
    }

    @Test
    void aDetailRefersToItsSummaryWithNoCascadeAndTheKeyIsTrusted() {
        assertThat(db.admin().sql("""
                        SELECT CONCAT(OBJECT_SCHEMA_NAME(f.referenced_object_id), '.', OBJECT_NAME(f.referenced_object_id),
                            '|', f.delete_referential_action_desc, '|', f.update_referential_action_desc, '|',
                            f.is_disabled, '|', f.is_not_trusted, '|',
                            (SELECT STRING_AGG(CONCAT(COL_NAME(c.parent_object_id, c.parent_column_id), '>',
                                COL_NAME(c.referenced_object_id, c.referenced_column_id)), ',')
                             FROM sys.foreign_key_columns c WHERE c.constraint_object_id = f.object_id))
                        FROM sys.foreign_keys f
                        WHERE f.parent_object_id = OBJECT_ID('pauth.auth_detail') AND f.name = 'FK_auth_detail_summary'""")
                .query(String.class).single())
                .isEqualTo("pauth.auth_summary|NO_ACTION|NO_ACTION|0|0|acct_id>acct_id");
    }

    @Test
    void aSummaryWithDetailsCannotBeDeletedAndDetailIdsAreNeverReused() {
        summary(ACCT);
        long first = detail(ACCT, 77814, 899999999);
        assertThatThrownBy(() -> db.admin().sql("DELETE FROM pauth.auth_summary").update())
                .hasMessageContaining("FK_auth_detail_summary")
                .rootCause().isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(547));
        assertThat(db.admin().sql("SELECT COUNT(*) FROM pauth.auth_summary").query(Integer.class).single())
                .as("the failed delete changes nothing").isOne();
        assertThat(db.admin().sql("SELECT auth_id FROM pauth.auth_detail").query(Long.class).list())
                .containsExactly(first);

        db.admin().sql("DELETE FROM pauth.auth_detail").update();
        assertThat(db.admin().sql("DELETE FROM pauth.auth_summary").update()).isOne();
        summary(ACCT);
        assertThat(detail(ACCT, 77814, 899999999)).isGreaterThan(first);
    }

    @Test
    void theFraudCopyHoldsTheClearCardNumberAndOutlivesTheDetail() {
        summary(ACCT);
        long authId = detail(ACCT, 77814, 899999999);
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        assertThat(authorization.sql("""
                        UPDATE pauth.auth_detail SET auth_fraud = 'F', fraud_rpt_date = '2022-07-06', fraud_changed_by = 'ADMIN001'
                        WHERE auth_id = :id""").param("id", authId).update()).isEqualTo(1);
        assertThat(authorization.sql("""
                        INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_fraud, fraud_rpt_date, acct_id, reported_by)
                        SELECT auth_id, card_num, processed_at, auth_fraud, fraud_rpt_date, acct_id, fraud_changed_by
                        FROM pauth.auth_detail WHERE auth_id = :id""").param("id", authId).update()).isEqualTo(1);
        db.admin().sql("DELETE FROM pauth.auth_detail").update();
        db.admin().sql("DELETE FROM pauth.auth_summary").update();
        assertThat(db.admin().sql("SELECT card_num FROM pauth.auth_fraud WHERE auth_id = :id").param("id", authId)
                .query(String.class).single()).isEqualTo(CARD);
    }

    @Test
    void theAuthorizationLoginChangesOnlyTheFraudColumnsAndNeverReadsTheCvv() {
        summary(ACCT);
        long authId = detail(ACCT, 77814, 899999999);
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        assertThatThrownBy(() -> authorization.sql("UPDATE pauth.auth_detail SET approved_amt = 0 WHERE auth_id = :id")
                .param("id", authId).update()).hasMessageContaining("permission was denied");
        assertThatThrownBy(() -> authorization.sql("SELECT cvv_cd FROM core.card").query().listOfRows())
                .hasMessageContaining("permission was denied");
        assertThat(authorization.sql("SELECT card_num, acct_id, active_status, expiration_date FROM core.card")
                .query().listOfRows()).hasSize(1);
    }

    /** Type, size and nullability of a column, e.g. {@code decimal(12,2) not null} or {@code char(8) null}. */
    static String column(JdbcClient jdbc, String table, String name) {
        return jdbc.sql("""
                        SELECT TYPE_NAME(system_type_id) + '('
                            + CASE TYPE_NAME(system_type_id)
                                WHEN 'decimal' THEN CAST(precision AS VARCHAR(3)) + ',' + CAST(scale AS VARCHAR(3))
                                WHEN 'datetime2' THEN CAST(scale AS VARCHAR(3))
                                ELSE CAST(max_length AS VARCHAR(5)) END
                            + ')' + CASE WHEN is_nullable = 1 THEN ' null' ELSE ' not null' END
                        FROM sys.columns WHERE object_id = OBJECT_ID(:table) AND name = :name""")
                .param("table", table).param("name", name).query(String.class).single();
    }

    private void summary(String acct) {
        db.admin().sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                VALUES (:acct, '000000001', 1000.00, 500.00, 0, 0, 0, 0, 0, 0)""").param("acct", acct).update();
    }

    private long detail(String acct, int date9c, int time9c) {
        return db.admin().sql("""
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time,
                    card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, match_status)
                OUTPUT inserted.auth_id
                VALUES (:acct, '2022-07-06T10:00:00', :date9c, :time9c, '220706', '100000', :card, '0100', '0925',
                    'AUTHRQ', 'POS   ', '000001', '00', '0000', '000000', 12.34, 12.34, '5411', '840', '01',
                    '000000000000001', 'MERCHANT', 'CITY', 'TX', '75001', '000000000000001', 'P')""")
                .param("acct", acct).param("date9c", date9c).param("time9c", time9c).param("card", CARD)
                .query(Long.class).single();
    }
}
