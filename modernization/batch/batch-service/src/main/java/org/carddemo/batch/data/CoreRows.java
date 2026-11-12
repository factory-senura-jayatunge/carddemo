package org.carddemo.batch.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * <p>Two rules are applied here. DEF-ACUP-002 (D-11): an account row keeps
 * positions 103-122 as read in {@code legacy_zip_group} next to the values the
 * load chose for {@code addr_zip} and {@code group_id} ({@link ZipGroup}); the
 * export writes that text back while both values are unchanged, per row and
 * with no mode, and the {@code CVACT01Y} layout otherwise. Dates: a blank date
 * is {@code NULL}; anything else must be a valid {@code YYYY-MM-DD} date (the
 * load checks that before it inserts). A text value is stored unchanged or the
 * insert fails ({@link ColumnCapacity}).
 */
public final class CoreRows {

    private final JdbcClient db;
    private final Map<String, ColumnCapacity> capacities = new HashMap<>();

    public CoreRows(JdbcClient db) {
        this.db = db;
    }

    // --- account --------------------------------------------------------------------------

    /**
     * One exported account: the record, the row's {@code zip_group_origin} ({@code null} when the
     * row was not loaded from a file) and whether 103-122 is the load-time text unchanged.
     */
    public record AccountExport(AccountRecord record, String origin, boolean restored) {
    }

    /** Inserts a record stored as read ({@code CVACT01Y}, origin {@code R}). */
    public void insert(AccountRecord a) {
        insert(a, ZipGroup.classify(ZipGroup.asRead(a), AccountLayout.CVACT01Y));
    }

    /** Inserts a record with the 103-122 values the load classified from it. */
    public void insert(AccountRecord a, ZipGroup.Stored zipGroup) {
        insert("core.account", accountRow(a, zipGroup));
    }

    static Map<String, Object> accountRow(AccountRecord a, ZipGroup.Stored zipGroup) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("acct_id", a.acctId());
        row.put("active_status", a.activeStatus());
        row.put("curr_bal", a.currBal());
        row.put("credit_limit", a.creditLimit());
        row.put("cash_credit_limit", a.cashCreditLimit());
        row.put("open_date", date(a.openDate()));
        row.put("expiration_date", date(a.expirationDate()));
        row.put("reissue_date", date(a.reissueDate()));
        row.put("curr_cyc_credit", a.currCycCredit());
        row.put("curr_cyc_debit", a.currCycDebit());
        row.put("addr_zip", zipGroup.addrZip());
        row.put("group_id", zipGroup.groupId());
        row.put("legacy_zip_group", zipGroup.legacyZipGroup());
        row.put("zip_group_origin", zipGroup.origin());
        row.put("legacy_filler", a.legacyFiller());
        return row;
    }

    /** The accounts as an export writes them (contract v2 section 3). */
    public List<AccountRecord> accounts() {
        return accountExports().stream().map(AccountExport::record).toList();
    }

    /** The accounts as an export writes them, with each row's provenance and export rule. */
    public List<AccountExport> accountExports() {
        return db.sql("""
                SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date,
                       expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id,
                       legacy_zip_group, zip_group_origin, legacy_filler
                FROM core.account ORDER BY acct_id
                """)
                .query((rs, n) -> {
                    String origin = rs.getString("zip_group_origin");
                    ZipGroup.Exported zipGroup = ZipGroup.export(rs.getString("addr_zip"), rs.getString("group_id"),
                            rs.getString("legacy_zip_group"), origin);
                    AccountRecord record = new AccountRecord(
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
                            ZipGroup.stripTrailingSpaces(ZipGroup.zip(zipGroup.zipGroup())),
                            ZipGroup.stripTrailingSpaces(ZipGroup.group(zipGroup.zipGroup())),
                            rs.getString("legacy_filler"));
                    return new AccountExport(record, origin, zipGroup.restored());
                })
                .list();
    }

    // --- customer -------------------------------------------------------------------------

    public void insert(CustomerRecord c) {
        insert("core.customer", customerRow(c));
    }

    static Map<String, Object> customerRow(CustomerRecord c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cust_id", c.custId());
        row.put("first_name", c.firstName());
        row.put("middle_name", c.middleName());
        row.put("last_name", c.lastName());
        row.put("addr_line_1", c.addrLine1());
        row.put("addr_line_2", c.addrLine2());
        row.put("addr_line_3", c.addrLine3());
        row.put("state_cd", c.stateCd());
        row.put("country_cd", c.countryCd());
        row.put("zip", c.zip());
        row.put("phone_1", c.phone1());
        row.put("phone_2", c.phone2());
        row.put("ssn", c.ssn());
        row.put("govt_issued_id", c.govtIssuedId());
        row.put("dob", date(c.dob()));
        row.put("eft_account_id", c.eftAccountId());
        row.put("pri_card_holder_ind", c.priCardHolderInd());
        row.put("fico_score", c.ficoScore());
        return row;
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
        insert("core.card", cardRow(c));
    }

    static Map<String, Object> cardRow(CardRecord c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("card_num", c.cardNum());
        row.put("acct_id", c.acctId());
        row.put("cvv_cd", c.cvvCd());
        row.put("embossed_name", c.embossedName());
        row.put("expiration_date", c.expirationDate());
        row.put("active_status", c.activeStatus());
        row.put("legacy_filler", c.legacyFiller());
        return row;
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
        insert("core.card_xref", cardXrefRow(x));
    }

    static Map<String, Object> cardXrefRow(CardXrefRecord x) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("card_num", x.cardNum());
        row.put("cust_id", x.custId());
        row.put("acct_id", x.acctId());
        return row;
    }

    public List<CardXrefRecord> cardXrefs() {
        return db.sql("SELECT card_num, cust_id, acct_id FROM core.card_xref ORDER BY card_num")
                .query((rs, n) -> new CardXrefRecord(rs.getString("card_num"), rs.getString("cust_id"),
                        rs.getString("acct_id")))
                .list();
    }

    // --- category balance -----------------------------------------------------------------

    public void insert(TcatBalRecord t) {
        insert("core.tcat_bal", categoryBalanceRow(t));
    }

    static Map<String, Object> categoryBalanceRow(TcatBalRecord t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("acct_id", t.acctId());
        row.put("type_cd", t.typeCd());
        row.put("cat_cd", t.catCd());
        row.put("balance", t.balance());
        row.put("legacy_filler", t.legacyFiller());
        return row;
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

    /**
     * Inserts one row given column by column, after checking that the table has every column
     * ({@link MissingColumnException}) and that every text column holds its value unchanged; a
     * value it cannot hold fails with {@link ColumnCapacityException}.
     */
    private void insert(String table, Map<String, Object> row) {
        insertRow(db, capacities.computeIfAbsent(table, t -> ColumnCapacity.of(db, t)), table, row);
    }

    static void insertRow(JdbcClient db, ColumnCapacity capacity, String table, Map<String, Object> row) {
        capacity.requireColumns(row.keySet());
        row.forEach(capacity::require);
        String columns = String.join(", ", row.keySet());
        String values = row.keySet().stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        var spec = db.sql("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")");
        for (Map.Entry<String, Object> e : row.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        spec.update();
    }

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
