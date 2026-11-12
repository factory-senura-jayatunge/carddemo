package org.carddemo.platform.testsupport;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts rows into the core tables as the admin login. Values not given get neutral defaults;
 * tests state every value their assertions depend on.
 */
public final class CoreFixtures {

    private static final String[] DELETE_ORDER = {
        "pauth.outbox", "pauth.processed_message", "pauth.auth_fraud", "pauth.auth_detail", "pauth.auth_summary",
        "core.daily_reject", "core.daily_tran", "billpay.payment", "core.[transaction]", "core.tcat_bal",
        "core.card_xref", "core.card", "core.customer", "core.account", "ops.batch_window",
        "batch.account_statement", "batch.statement_capture", "batch.statement_run", "batch.cycle",
        "ops.audit",
    };

    private final JdbcClient jdbc;

    CoreFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Empties every table of the foundation, Bill Pay and pending-authorization schemas and the
     * cycles, children first, and restarts the ID sequence at its lowest value, as a load of no
     * transactions leaves it.
     */
    public CoreFixtures deleteAll() {
        for (String table : DELETE_ORDER) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        return restartTranIdSequence(tranIdSequenceMinimum());
    }

    /**
     * Every row version of the table, in version order, as the hex of its 8 bytes. They are read as
     * bytes because a CONVERT to text that does not fit gives every row the same blank or cut
     * value, and a before-and-after comparison of those passes whatever was written.
     */
    public List<String> rowVersions(String table) {
        List<String> versions = jdbc.sql("SELECT row_version FROM " + table + " ORDER BY row_version")
                .query((rs, n) -> HexFormat.of().formatHex(rs.getBytes(1))).list();
        if (versions.stream().anyMatch(v -> v.length() != 16) || new HashSet<>(versions).size() != versions.size()) {
            throw new IllegalStateException("row versions of " + table + " are not distinct 8-byte values");
        }
        return versions;
    }

    /** The {@code MINVALUE} of {@code core.tran_id_seq}: the first fixed-mode transaction ID. */
    public long tranIdSequenceMinimum() {
        return jdbc.sql("SELECT CAST(minimum_value AS BIGINT) FROM sys.sequences WHERE object_id = OBJECT_ID('core.tran_id_seq')")
                .query(Long.class).single();
    }

    /**
     * The next {@code NEXT VALUE FOR core.tran_id_seq}, as the load sets it above the highest loaded
     * ID. A value outside the sequence's range fails, as {@code ALTER SEQUENCE} does.
     */
    public CoreFixtures restartTranIdSequence(long next) {
        jdbc.sql("ALTER SEQUENCE core.tran_id_seq RESTART WITH " + next).update();
        return this;
    }

    /** Opens a batch window (mode WRITES_BLOCKED) held by a fresh run key {@code test/<uuid>}. */
    public CoreFixtures openBatchWindow() {
        // A window must name exactly one holder, a cycle or a run key, once V406 adds run_key. The
        // insert naming run_key is dynamic SQL because a batch that names a missing column does not
        // compile, so this works on databases with and without V406.
        jdbc.sql("""
                IF COL_LENGTH('ops.batch_window', 'run_key') IS NULL
                    INSERT INTO ops.batch_window (mode, opened_by) VALUES ('WRITES_BLOCKED', 'test');
                ELSE
                    EXEC sp_executesql
                        N'INSERT INTO ops.batch_window (mode, opened_by, run_key) VALUES (''WRITES_BLOCKED'', ''test'', @runKey)',
                        N'@runKey VARCHAR(250)', @runKey = :runKey;""")
                .param("runKey", "test/" + UUID.randomUUID())
                .update();
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

    public record Customer(String custId, String firstName, String middleName, String lastName, String addrLine1,
                           String addrLine2, String addrLine3, String stateCd, String countryCd, String zip,
                           String phone1, String phone2, String ssn, String govtIssuedId, LocalDate dob,
                           String eftAccountId, String priCardHolderInd, int ficoScore) {

        public static Customer of(String custId) {
            return new Customer(custId, "TEST", "", "CUSTOMER", "1 MAIN ST", "", "TOWN", "NY", "USA", "10001",
                    "(212)555-0100", "", "000000000", "", LocalDate.of(1980, 1, 1), "0000000000", "Y", 700);
        }

        public Customer withZip(String value) {
            return new Customer(custId, firstName, middleName, lastName, addrLine1, addrLine2, addrLine3, stateCd,
                    countryCd, value, phone1, phone2, ssn, govtIssuedId, dob, eftAccountId, priCardHolderInd, ficoScore);
        }

        public Customer withPhones(String first, String second) {
            return new Customer(custId, firstName, middleName, lastName, addrLine1, addrLine2, addrLine3, stateCd,
                    countryCd, zip, first, second, ssn, govtIssuedId, dob, eftAccountId, priCardHolderInd, ficoScore);
        }

        public Customer withDob(LocalDate value) {
            return new Customer(custId, firstName, middleName, lastName, addrLine1, addrLine2, addrLine3, stateCd,
                    countryCd, zip, phone1, phone2, ssn, govtIssuedId, value, eftAccountId, priCardHolderInd, ficoScore);
        }
    }

    public CoreFixtures customer(String custId) {
        return customer(Customer.of(custId));
    }

    public CoreFixtures customer(Customer c) {
        jdbc.sql("""
                INSERT INTO core.customer (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
                    addr_line_3, state_cd, country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob,
                    eft_account_id, pri_card_holder_ind, fico_score)
                VALUES (:id, :first, :middle, :last, :addr1, :addr2, :addr3, :state, :country, :zip,
                    :phone1, :phone2, :ssn, :govt, :dob, :eft, :primary, :fico)""")
                .param("id", c.custId()).param("first", c.firstName()).param("middle", c.middleName())
                .param("last", c.lastName()).param("addr1", c.addrLine1()).param("addr2", c.addrLine2())
                .param("addr3", c.addrLine3()).param("state", c.stateCd()).param("country", c.countryCd())
                .param("zip", c.zip()).param("phone1", c.phone1()).param("phone2", c.phone2())
                .param("ssn", c.ssn()).param("govt", c.govtIssuedId()).param("dob", c.dob(), Types.DATE)
                .param("eft", c.eftAccountId()).param("primary", c.priCardHolderInd()).param("fico", c.ficoScore())
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

    /**
     * An online add waiting for the next cycle (D-27): origin ONLINE_ADD, cycle and seq NULL. Its
     * data fields differ from {@link #transaction}'s, so a reader shows which row it chose.
     */
    public CoreFixtures waitingOnlineAdd(String tranId, String cardNum, String amount) {
        jdbc.sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num,
                    orig_ts, proc_ts, raw_record)
                VALUES (NULL, NULL, 'ONLINE_ADD', 'USER0001', :id, '03', '0005', 'OPERATOR', :description,
                    :amount, '000000002', 'ADDED MERCHANT', 'CITY', '20002', :card,
                    '2026-11-05                ', '2026-11-06                ', :id)""")
                .param("id", tranId).param("description", "ONLINE ADD " + tranId)
                .param("amount", new BigDecimal(amount)).param("card", cardNum)
                .update();
        return this;
    }

    /**
     * A daily-file row claimed by the cycle of {@code businessDate} (created when missing) at the
     * cycle's next seq, as ingestDailyFileJob stages it before posting.
     */
    public CoreFixtures claimedDailyRow(String businessDate, String origin, String tranId, String cardNum) {
        jdbc.sql("""
                IF NOT EXISTS (SELECT 1 FROM batch.cycle WHERE business_date = :d)
                    INSERT INTO batch.cycle (business_date) VALUES (:d)""")
                .param("d", LocalDate.parse(businessDate)).update();
        jdbc.sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num,
                    orig_ts, proc_ts, raw_record)
                SELECT c.cycle_id, COALESCE((SELECT MAX(seq) FROM core.daily_tran d WHERE d.cycle_id = c.cycle_id), 0) + 1,
                    :origin, NULL, :id, '01', '0001', 'POS TERM', 'CLAIMED ROW', 1.00, '000000003', 'CLAIMED',
                    'TOWN', '10001', :card, '2022-07-05                ', '2022-07-06                ', :id
                FROM batch.cycle c WHERE c.business_date = :d""")
                .param("d", LocalDate.parse(businessDate)).param("origin", origin).param("id", tranId)
                .param("card", cardNum)
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
