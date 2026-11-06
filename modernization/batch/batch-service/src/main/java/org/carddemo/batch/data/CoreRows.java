package org.carddemo.batch.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.carddemo.platform.legacyformat.LegacyTimestamps;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The mapping between the legacy records and the core tables, in both
 * directions: the load inserts with it and the exporter (verify-data, later
 * CBEXPORT) reads with it, so a value the tables cannot hold shows up as a
 * re-export difference.
 *
 * <p>Two rules are applied here. D-11: the group ID that every sample account
 * holds at the ZIP position (INV-039) goes to {@code group_id} and
 * {@code addr_zip} stays empty; the export puts them back. Dates: a blank date
 * is {@code NULL}; anything else must be a valid {@code YYYY-MM-DD} date (the
 * load checks that before it inserts).
 */
public final class CoreRows {

    private final JdbcClient db;

    public CoreRows(JdbcClient db) {
        this.db = db;
    }

    // --- account --------------------------------------------------------------------------

    public void insert(AccountRecord a) {
        db.sql("""
                INSERT INTO core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
                    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit,
                    addr_zip, group_id, legacy_filler)
                VALUES (:acctId, :activeStatus, :currBal, :creditLimit, :cashCreditLimit,
                    :openDate, :expirationDate, :reissueDate, :currCycCredit, :currCycDebit,
                    '', :groupId, :legacyFiller)
                """)
                .param("acctId", a.acctId())
                .param("activeStatus", a.activeStatus())
                .param("currBal", a.currBal())
                .param("creditLimit", a.creditLimit())
                .param("cashCreditLimit", a.cashCreditLimit())
                .param("openDate", date(a.openDate()))
                .param("expirationDate", date(a.expirationDate()))
                .param("reissueDate", date(a.reissueDate()))
                .param("currCycCredit", a.currCycCredit())
                .param("currCycDebit", a.currCycDebit())
                .param("groupId", a.addrZip())
                .param("legacyFiller", a.legacyFiller())
                .update();
    }

    public List<AccountRecord> accounts() {
        return db.sql("""
                SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date,
                       expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id,
                       legacy_filler
                FROM core.account ORDER BY acct_id
                """)
                .query((rs, n) -> new AccountRecord(
                        rs.getString("acct_id"),
                        rs.getString("active_status"),
                        rs.getBigDecimal("curr_bal"),
                        rs.getBigDecimal("credit_limit"),
                        rs.getBigDecimal("cash_credit_limit"),
                        text(rs, "open_date"),
                        text(rs, "expiration_date"),
                        text(rs, "reissue_date"),
                        rs.getBigDecimal("curr_cyc_credit"),
                        rs.getBigDecimal("curr_cyc_debit"),
                        rs.getString("group_id"),
                        rs.getString("addr_zip"),
                        rs.getString("legacy_filler")))
                .list();
    }

    // --- customer -------------------------------------------------------------------------

    public void insert(CustomerRecord c) {
        db.sql("""
                INSERT INTO core.customer (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
                    addr_line_3, state_cd, country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob,
                    eft_account_id, pri_card_holder_ind, fico_score)
                VALUES (:custId, :firstName, :middleName, :lastName, :addr1, :addr2, :addr3, :state, :country,
                    :zip, :phone1, :phone2, :ssn, :govtId, :dob, :eft, :pri, :fico)
                """)
                .param("custId", c.custId())
                .param("firstName", c.firstName())
                .param("middleName", c.middleName())
                .param("lastName", c.lastName())
                .param("addr1", c.addrLine1())
                .param("addr2", c.addrLine2())
                .param("addr3", c.addrLine3())
                .param("state", c.stateCd())
                .param("country", c.countryCd())
                .param("zip", c.zip())
                .param("phone1", c.phone1())
                .param("phone2", c.phone2())
                .param("ssn", c.ssn())
                .param("govtId", c.govtIssuedId())
                .param("dob", date(c.dob()))
                .param("eft", c.eftAccountId())
                .param("pri", c.priCardHolderInd())
                .param("fico", c.ficoScore())
                .update();
    }

    public List<CustomerRecord> customers() {
        return db.sql("""
                SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3,
                       state_cd, country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob, eft_account_id,
                       pri_card_holder_ind, fico_score
                FROM core.customer ORDER BY cust_id
                """)
                .query((rs, n) -> new CustomerRecord(
                        rs.getString("cust_id"),
                        rs.getString("first_name"),
                        rs.getString("middle_name"),
                        rs.getString("last_name"),
                        rs.getString("addr_line_1"),
                        rs.getString("addr_line_2"),
                        rs.getString("addr_line_3"),
                        rs.getString("state_cd"),
                        rs.getString("country_cd"),
                        rs.getString("zip"),
                        rs.getString("phone_1"),
                        rs.getString("phone_2"),
                        rs.getString("ssn"),
                        rs.getString("govt_issued_id"),
                        text(rs, "dob"),
                        rs.getString("eft_account_id"),
                        rs.getString("pri_card_holder_ind"),
                        rs.getInt("fico_score")))
                .list();
    }

    // --- card -----------------------------------------------------------------------------

    public void insert(CardRecord c) {
        db.sql("""
                INSERT INTO core.card (card_num, acct_id, cvv_cd, embossed_name, expiration_date, active_status,
                    legacy_filler)
                VALUES (:cardNum, :acctId, :cvv, :name, :expiration, :status, :legacyFiller)
                """)
                .param("cardNum", c.cardNum())
                .param("acctId", c.acctId())
                .param("cvv", c.cvvCd())
                .param("name", c.embossedName())
                .param("expiration", c.expirationDate())
                .param("status", c.activeStatus())
                .param("legacyFiller", c.legacyFiller())
                .update();
    }

    public List<CardRecord> cards() {
        return db.sql("""
                SELECT card_num, acct_id, cvv_cd, embossed_name, expiration_date, active_status, legacy_filler
                FROM core.card ORDER BY card_num
                """)
                .query((rs, n) -> new CardRecord(
                        rs.getString("card_num"),
                        rs.getString("acct_id"),
                        rs.getString("cvv_cd"),
                        rs.getString("embossed_name"),
                        rs.getString("expiration_date"),
                        rs.getString("active_status"),
                        rs.getString("legacy_filler")))
                .list();
    }

    // --- card cross-reference -------------------------------------------------------------

    public void insert(CardXrefRecord x) {
        db.sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:cardNum, :custId, :acctId)")
                .param("cardNum", x.cardNum())
                .param("custId", x.custId())
                .param("acctId", x.acctId())
                .update();
    }

    public List<CardXrefRecord> cardXrefs() {
        return db.sql("SELECT card_num, cust_id, acct_id FROM core.card_xref ORDER BY card_num")
                .query((rs, n) -> new CardXrefRecord(rs.getString("card_num"), rs.getString("cust_id"),
                        rs.getString("acct_id")))
                .list();
    }

    // --- category balance -----------------------------------------------------------------

    public void insert(TcatBalRecord t) {
        db.sql("""
                INSERT INTO core.tcat_bal (acct_id, type_cd, cat_cd, balance, legacy_filler)
                VALUES (:acctId, :typeCd, :catCd, :balance, :legacyFiller)
                """)
                .param("acctId", t.acctId())
                .param("typeCd", t.typeCd())
                .param("catCd", t.catCd())
                .param("balance", t.balance())
                .param("legacyFiller", t.legacyFiller())
                .update();
    }

    public List<TcatBalRecord> categoryBalances() {
        return db.sql("""
                SELECT acct_id, type_cd, cat_cd, balance, legacy_filler
                FROM core.tcat_bal ORDER BY acct_id, type_cd, cat_cd
                """)
                .query((rs, n) -> new TcatBalRecord(rs.getString("acct_id"), rs.getString("type_cd"),
                        rs.getString("cat_cd"), rs.getBigDecimal("balance"), rs.getString("legacy_filler")))
                .list();
    }

    // --- helpers --------------------------------------------------------------------------

    /** A blank text date is NULL; the caller has checked that any other value is a valid date. */
    static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LegacyTimestamps.parseDate(text)
                .orElseThrow(() -> new IllegalArgumentException("not a YYYY-MM-DD date"));
    }

    private static String text(ResultSet rs, String column) throws SQLException {
        LocalDate d = rs.getObject(column, LocalDate.class);
        return d == null ? "" : LegacyTimestamps.formatDate(d);
    }
}
