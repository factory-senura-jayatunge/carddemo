package org.carddemo.platform.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts rows into the core tables as the admin login. Values not given get neutral defaults;
 * tests state every value their assertions depend on.
 */
public final class CoreFixtures {

    private static final String[] DELETE_ORDER = {
        "billpay.payment", "core.[transaction]", "core.tcat_bal", "core.card_xref", "core.card", "core.customer",
        "core.account", "ops.batch_window",
    };

    private final JdbcClient jdbc;

    CoreFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empties every table of the foundation and Bill Pay schemas, children first, and restarts the ID sequence at 1. */
    public CoreFixtures deleteAll() {
        for (String table : DELETE_ORDER) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        return restartTranIdSequence(1);
    }

    /** The next {@code NEXT VALUE FOR core.tran_id_seq}, as the load sets it above the highest loaded ID. */
    public CoreFixtures restartTranIdSequence(long next) {
        jdbc.sql("ALTER SEQUENCE core.tran_id_seq RESTART WITH " + Math.max(1, next)).update();
        return this;
    }

    /** Opens a batch window (mode WRITES_BLOCKED). */
    public CoreFixtures openBatchWindow() {
        jdbc.sql("INSERT INTO ops.batch_window (mode, opened_by) VALUES ('WRITES_BLOCKED', 'test')").update();
        return this;
    }

    public CoreFixtures closeBatchWindows() {
        jdbc.sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        return this;
    }

    public record Account(String acctId, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
                          BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate,
                          LocalDate reissueDate, BigDecimal currCycCredit, BigDecimal currCycDebit,
                          String addrZip, String groupId) {

        public static Account of(String acctId, String currBal) {
            return new Account(acctId, "Y", new BigDecimal(currBal), new BigDecimal("2020.00"),
                    new BigDecimal("1020.00"), LocalDate.of(2014, 11, 20), LocalDate.of(2025, 5, 20),
                    LocalDate.of(2025, 5, 20), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    "A000000000", "");
        }

        public Account withCycleTotals(String credit, String debit) {
            return new Account(acctId, activeStatus, currBal, creditLimit, cashCreditLimit, openDate,
                    expirationDate, reissueDate, new BigDecimal(credit), new BigDecimal(debit), addrZip, groupId);
        }
    }

    public CoreFixtures account(Account a) {
        jdbc.sql("""
                INSERT INTO core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id)
                VALUES (:id, :status, :bal, :limit, :cash, :open, :exp, :reissue, :credit, :debit, :zip, :grp)""")
                .param("id", a.acctId()).param("status", a.activeStatus()).param("bal", a.currBal())
                .param("limit", a.creditLimit()).param("cash", a.cashCreditLimit()).param("open", a.openDate())
                .param("exp", a.expirationDate()).param("reissue", a.reissueDate())
                .param("credit", a.currCycCredit()).param("debit", a.currCycDebit())
                .param("zip", a.addrZip()).param("grp", a.groupId())
                .update();
        return this;
    }

    public CoreFixtures customer(String custId) {
        jdbc.sql("""
                INSERT INTO core.customer (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
                    addr_line_3, state_cd, country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob,
                    eft_account_id, pri_card_holder_ind, fico_score)
                VALUES (:id, 'TEST', '', 'CUSTOMER', '1 MAIN ST', '', 'TOWN', 'NY', 'USA', '10001',
                    '(212)555-0100', '', '000000000', '', '1980-01-01', '0000000000', 'Y', 700)""")
                .param("id", custId)
                .update();
        return this;
    }

    /** A card and its cross-reference row; the customer must exist. */
    public CoreFixtures card(String cardNum, String acctId, String custId) {
        jdbc.sql("""
                INSERT INTO core.card (card_num, acct_id, cvv_cd, embossed_name, expiration_date, active_status)
                VALUES (:card, :acct, '000', 'TEST CUSTOMER', '2030-01-31', 'Y')""")
                .param("card", cardNum).param("acct", acctId)
                .update();
        jdbc.sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:card, :cust, :acct)")
                .param("card", cardNum).param("cust", custId).param("acct", acctId)
                .update();
        return this;
    }

    /** One loaded transaction in the master, with fixed neutral content apart from the key, card and amount. */
    public CoreFixtures transaction(String tranId, String cardNum, String amount) {
        jdbc.sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, '01', '0001', 'POS TERM', 'FIXTURE PURCHASE', :amount, '000000001', 'FIXTURE',
                    'TOWN', '10001', :card, '2022-07-05 10:00:00', '2022-07-05 10:00:00', 'LOADED', 1)""")
                .param("id", tranId).param("amount", new BigDecimal(amount)).param("card", cardNum)
                .update();
        return this;
    }

    public CoreFixtures categoryBalance(String acctId, String typeCd, String catCd, String balance) {
        jdbc.sql("INSERT INTO core.tcat_bal (acct_id, type_cd, cat_cd, balance) VALUES (:a, :t, :c, :b)")
                .param("a", acctId).param("t", typeCd).param("c", catCd).param("b", new BigDecimal(balance))
                .update();
        return this;
    }
}
