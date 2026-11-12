package org.carddemo.batch.pauth;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pending-authorization rows inserted as the admin login. Each detail gets a key part
 * ({@code auth_time_9c}) unique in the JVM, so fixtures never collide on the 9-complement key.
 * The card number is synthetic and the same for every detail.
 */
final class PauthFixtures {

    static final String CARD = "4000002000000000";

    private static final AtomicInteger KEYS = new AtomicInteger(1);

    private static final String DETAIL_COLUMNS = """
            acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date, orig_time, card_num, auth_type,
            card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code, auth_resp_reason,
            processing_code, transaction_amt, approved_amt, merchant_category_code, acqr_country_code,
            pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id,
            match_status, auth_fraud, matched_tran_pk, matched_tran_id, matched_cycle_id, matched_at""";

    private final JdbcClient db;
    private final CoreFixtures core;

    PauthFixtures(TestDatabase database) {
        this.db = database.admin();
        this.core = database.fixtures();
    }

    static String acct(int n) {
        return String.format("%011d", n);
    }

    /** The account and its summary with the given totals; limits 5000.00 and 1000.00, cash balance 0. */
    PauthFixtures summary(String acctId, String creditBalance, int approvedCnt, String approvedAmt, int declinedCnt,
            String declinedAmt) {
        return summary(acctId, "5000.00", "1000.00", creditBalance, "0.00", approvedCnt, approvedAmt, declinedCnt,
                declinedAmt);
    }

    PauthFixtures summary(String acctId, String creditLimit, String cashLimit, String creditBalance,
            String cashBalance, int approvedCnt, String approvedAmt, int declinedCnt, String declinedAmt) {
        core.account(CoreFixtures.Account.of(acctId, "0.00"));
        db.sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                VALUES (:acct, '000000001', :creditLimit, :cashLimit, :creditBalance, :cashBalance, :approvedCnt,
                    :declinedCnt, :approvedAmt, :declinedAmt)""")
                .param("acct", acctId).param("creditLimit", new BigDecimal(creditLimit))
                .param("cashLimit", new BigDecimal(cashLimit)).param("creditBalance", new BigDecimal(creditBalance))
                .param("cashBalance", new BigDecimal(cashBalance)).param("approvedCnt", approvedCnt)
                .param("declinedCnt", declinedCnt).param("approvedAmt", new BigDecimal(approvedAmt))
                .param("declinedAmt", new BigDecimal(declinedAmt))
                .update();
        return this;
    }

    /** One detail; a match status M gets the matched-transaction columns V503 requires. */
    long detail(String acctId, LocalDateTime processedAt, String resp, String match, String fraud, String approvedAmt,
            String transactionAmt) {
        int key = KEYS.getAndIncrement();
        boolean matched = match.equals("M");
        return db.sql("INSERT INTO pauth.auth_detail (" + DETAIL_COLUMNS + """
                ) OUTPUT inserted.auth_id
                VALUES (:acct, :at, 0, :key, '240101', '120000', :card, 'AUTH', '0130', 'MSGREQ', 'POS   ', '000001',
                    :resp, '0000', '000000', :requested, :approved, '5411', '840', '01', 'MERCHANT0000001',
                    'FIXTURE MERCHANT', 'TOWN', 'NY', '10001', 'TRAN00000000001', :match, :fraud,
                    :matchedPk, :matchedId, :matchedCycle, :matchedAt)""")
                .param("acct", acctId).param("at", processedAt).param("key", key).param("card", CARD)
                .param("resp", resp).param("requested", new BigDecimal(transactionAmt))
                .param("approved", new BigDecimal(approvedAmt)).param("match", match).param("fraud", fraud)
                .param("matchedPk", matched ? (Object) (long) key : null, java.sql.Types.BIGINT)
                .param("matchedId", matched ? "0000000000000001" : null, java.sql.Types.CHAR)
                .param("matchedCycle", matched ? (Object) 1 : null, java.sql.Types.INTEGER)
                .param("matchedAt", matched ? LocalDateTime.of(2022, 7, 6, 0, 0) : null, java.sql.Types.TIMESTAMP)
                .query(Long.class).single();
    }

    /** {@code count} approved, unmatched details of {@code amount} each, all at {@code processedAt}. */
    void approvals(String acctId, int count, LocalDateTime processedAt, String amount) {
        int base = KEYS.getAndAdd(count);
        db.sql("INSERT INTO pauth.auth_detail (" + DETAIL_COLUMNS + """
                )
                SELECT TOP (:n) :acct, :at, 0, :base + ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1, '240101', '120000',
                    :card, 'AUTH', '0130', 'MSGREQ', 'POS   ', '000001', '00', '0000', '000000', :amt, :amt, '5411',
                    '840', '01', 'MERCHANT0000001', 'FIXTURE MERCHANT', 'TOWN', 'NY', '10001', 'TRAN00000000001', 'P',
                    ' ', NULL, NULL, NULL, NULL
                FROM sys.all_objects AS a CROSS JOIN sys.all_objects AS b""")
                .param("n", count).param("acct", acctId).param("at", processedAt).param("base", base)
                .param("card", CARD).param("amt", new BigDecimal(amount))
                .update();
    }

    /**
     * An approved, unmatched detail with the lowest key an account can hold (9c key parts 0 and 0),
     * as the newest detail has under the nine's complement. No other fixture uses key part 0.
     */
    void lowestKeyApproval(String acctId, LocalDateTime processedAt, String amount) {
        db.sql("INSERT INTO pauth.auth_detail (" + DETAIL_COLUMNS + """
                ) VALUES (:acct, :at, 0, 0, '240101', '120000', :card, 'AUTH', '0130', 'MSGREQ', 'POS   ', '000001',
                    '00', '0000', '000000', :amt, :amt, '5411', '840', '01', 'MERCHANT0000001', 'FIXTURE MERCHANT',
                    'TOWN', 'NY', '10001', 'TRAN00000000001', 'P', ' ', NULL, NULL, NULL, NULL)""")
                .param("acct", acctId).param("at", processedAt).param("card", CARD)
                .param("amt", new BigDecimal(amount))
                .update();
    }

    /** A detail key part no other fixture uses, for rows a test inserts itself. */
    static int nextKey() {
        return KEYS.getAndIncrement();
    }

    boolean summaryExists(String acctId) {
        return db.sql("SELECT COUNT(*) FROM pauth.auth_summary WHERE acct_id = :a").param("a", acctId)
                .query(Integer.class).single() == 1;
    }

    Map<String, Object> summaryRow(String acctId) {
        return db.sql("""
                SELECT credit_balance, cash_balance, approved_auth_cnt, approved_auth_amt, declined_auth_cnt,
                    declined_auth_amt FROM pauth.auth_summary WHERE acct_id = :a""")
                .param("a", acctId).query().singleRow();
    }

    long details(String acctId) {
        return db.sql("SELECT COUNT_BIG(*) FROM pauth.auth_detail WHERE acct_id = :a").param("a", acctId)
                .query(Long.class).single();
    }

    long count(String table) {
        return db.sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single();
    }
}
