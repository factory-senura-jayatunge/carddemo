package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The D-33 match columns of pauth.auth_detail (V503). */
class PauthMatchSchemaTest {

    private static final String ACCT = "00000000001";
    private static final String CARD = "4111111111111111";
    private static final String MATCH = """
            UPDATE pauth.auth_detail
            SET match_status = 'M', matched_tran_pk = :pk, matched_tran_id = '0000000000000001',
                matched_cycle_id = 7, matched_at = SYSUTCDATETIME()
            WHERE auth_id = :id""";

    private final TestDatabase db = TestDatabase.shared();
    private final JdbcClient admin = db.admin();

    @BeforeEach
    void accountWithASummary() {
        db.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00"))
                .customer("000000001")
                .card(CARD, ACCT, "000000001");
        admin.sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                VALUES (:acct, '000000001', 1000.00, 500.00, 0, 0, 0, 0, 0, 0)""").param("acct", ACCT).update();
    }

    @Test
    void aMatchSetsAllFourColumnsAndARerunClearsThemAll() {
        long authId = detail(899999999);
        assertThat(admin.sql(MATCH).param("pk", 41L).param("id", authId).update()).isEqualTo(1);
        assertThat(admin.sql("""
                        UPDATE pauth.auth_detail
                        SET match_status = 'P', matched_tran_pk = NULL, matched_tran_id = NULL, matched_cycle_id = NULL,
                            matched_at = NULL
                        WHERE auth_id = :id""").param("id", authId).update()).isEqualTo(1);
    }

    @Test
    void matchStatusMAndTheTransactionKeyGoTogether() {
        long authId = detail(899999999);
        assertThatThrownBy(() -> admin.sql("UPDATE pauth.auth_detail SET match_status = 'M' WHERE auth_id = :id")
                .param("id", authId).update()).hasMessageContaining("CK_auth_detail_matched_set");
        assertThatThrownBy(() -> admin.sql("""
                        UPDATE pauth.auth_detail
                        SET matched_tran_pk = 41, matched_tran_id = '0000000000000001', matched_cycle_id = 7,
                            matched_at = SYSUTCDATETIME()
                        WHERE auth_id = :id""").param("id", authId).update())
                .hasMessageContaining("CK_auth_detail_matched_clear");
        assertThatThrownBy(() -> admin.sql("UPDATE pauth.auth_detail SET match_status = 'M', matched_tran_pk = 41 WHERE auth_id = :id")
                .param("id", authId).update()).hasMessageContaining("CK_auth_detail_match_columns");
    }

    @Test
    void aTransactionSettlesAtMostOneApproval() {
        long first = detail(899999999);
        long second = detail(899999998);
        admin.sql(MATCH).param("pk", 41L).param("id", first).update();
        assertThatThrownBy(() -> admin.sql(MATCH).param("pk", 41L).param("id", second).update())
                .hasMessageContaining("UX_auth_detail_matched_tran");
        assertThat(admin.sql(MATCH).param("pk", 42L).param("id", second).update()).isEqualTo(1);
    }

    @Test
    void theCandidateIndexCoversOnlyPendingDetails() {
        assertThat(admin.sql("""
                        SELECT filter_definition FROM sys.indexes
                        WHERE object_id = OBJECT_ID('pauth.auth_detail') AND name = 'IX_auth_detail_pending'""")
                .query(String.class).single()).isEqualTo("([match_status]='P')");
        assertThat(admin.sql("""
                        SELECT c.name FROM sys.index_columns ic
                        JOIN sys.indexes i ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                        JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                        WHERE i.object_id = OBJECT_ID('pauth.auth_detail') AND i.name = 'IX_auth_detail_pending'
                        ORDER BY ic.is_included_column, ic.key_ordinal, c.name""").query(String.class).list())
                .containsExactly("card_num", "approved_amt", "merchant_id", "processed_at");
    }

    private long detail(int time9c) {
        return admin.sql("""
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time,
                    card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code,
                    auth_resp_reason, processing_code, transaction_amt, approved_amt, merchant_category_code,
                    acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                    merchant_zip, transaction_id, match_status)
                OUTPUT inserted.auth_id
                VALUES (:acct, '2022-07-05T10:00:00', 77813, :time9c, '220705', '100000', :card, '0100', '0925',
                    'AUTHRQ', 'POS   ', '000001', '00', '0000', '000000', 50.00, 50.00, '5411', '840', '01',
                    '000000000123456', 'MERCHANT', 'CITY', 'TX', '75001', '000000000000001', 'P')""")
                .param("acct", ACCT).param("time9c", time9c).param("card", CARD)
                .query(Long.class).single();
    }
}
