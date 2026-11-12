package org.carddemo.authorization.summary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The reads of the summary screen (COPAUS0C): plain SELECTs, no lock hints. Keys are bound as
 * {@code CAST(? AS CHAR(n))} and the list position as {@code CAST(? AS DATETIME2(3))}, so every read
 * seeks its index ({@code IX_xref_acct}, {@code IX_auth_detail_acct_newest}) instead of converting
 * the column.
 */
@Repository
public class AuthorizationSummaryRepository {

    public record AccountLimits(BigDecimal creditLimit, BigDecimal cashLimit) {
    }

    /** Only the columns the header shows: never ssn, govt_issued_id, dob or eft_account_id. */
    public record CustomerRow(String custId, String firstName, String middleName, String lastName, String addrLine1,
            String addrLine2, String addrLine3, String stateCd, String zip, String phone1) {
    }

    public record SummaryRow(int approvedCount, int declinedCount, BigDecimal approvedAmount,
            BigDecimal declinedAmount, BigDecimal creditBalance, BigDecimal cashBalance) {
    }

    public record ListedDetail(long authId, LocalDateTime processedAt, String transactionId, String origDate,
            String origTime, String authType, String respCode, String matchStatus, BigDecimal approvedAmount) {

        public ListPosition position() {
            return new ListPosition(processedAt, authId);
        }
    }

    private static final String LISTED = """
            SELECT TOP (:rows) auth_id, processed_at, transaction_id, orig_date, orig_time, auth_type, auth_resp_code,
                match_status, approved_amt
            FROM pauth.auth_detail WHERE acct_id = CAST(:acct AS CHAR(11))""";

    private static final RowMapper<ListedDetail> DETAIL = (rs, n) -> new ListedDetail(rs.getLong(1),
            rs.getObject(2, LocalDateTime.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getBigDecimal(9));

    private final JdbcClient jdbc;

    public AuthorizationSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountLimits> account(String acctId) {
        return jdbc.sql("SELECT credit_limit, cash_credit_limit FROM core.account WHERE acct_id = CAST(:acct AS CHAR(11))")
                .param("acct", acctId)
                .query((rs, n) -> new AccountLimits(rs.getBigDecimal(1), rs.getBigDecimal(2)))
                .optional();
    }

    /** The customer of the account's lowest card number (D-61): several cards are no error. */
    public Optional<String> firstCustomer(String acctId) {
        return jdbc.sql("""
                        SELECT TOP 1 cust_id FROM core.card_xref WHERE acct_id = CAST(:acct AS CHAR(11))
                        ORDER BY card_num""")
                .param("acct", acctId)
                .query(String.class)
                .optional();
    }

    public Optional<CustomerRow> customer(String custId) {
        return jdbc.sql("""
                        SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3,
                            state_cd, zip, phone_1
                        FROM core.customer WHERE cust_id = CAST(:cust AS CHAR(9))""")
                .param("cust", custId)
                .query((rs, n) -> new CustomerRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10)))
                .optional();
    }

    public Optional<SummaryRow> summary(String acctId) {
        return jdbc.sql("""
                        SELECT approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt,
                            credit_balance, cash_balance
                        FROM pauth.auth_summary WHERE acct_id = CAST(:acct AS CHAR(11))""")
                .param("acct", acctId)
                .query((rs, n) -> new SummaryRow(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)))
                .optional();
    }

    /** Up to {@code rows} details older than {@code from} (from the newest when null), newest first. */
    public List<ListedDetail> older(String acctId, ListPosition from, int rows) {
        String sql = LISTED + (from == null ? "" : " AND " + beyond("<", false))
                + " ORDER BY processed_at DESC, auth_id DESC";
        return bind(jdbc.sql(sql), acctId, from).param("rows", rows).query(DETAIL).list();
    }

    /** Up to {@code rows} details newer than {@code from}, nearest first. */
    public List<ListedDetail> newer(String acctId, ListPosition from, int rows) {
        String sql = LISTED + " AND " + beyond(">", false) + " ORDER BY processed_at ASC, auth_id ASC";
        return bind(jdbc.sql(sql), acctId, from).param("rows", rows).query(DETAIL).list();
    }

    public boolean anyNewer(String acctId, ListPosition from, boolean inclusive) {
        return exists(acctId, from, beyond(">", inclusive));
    }

    public boolean anyOlder(String acctId, ListPosition from, boolean inclusive) {
        return exists(acctId, from, beyond("<", inclusive));
    }

    private boolean exists(String acctId, ListPosition from, String condition) {
        return bind(jdbc.sql("SELECT TOP 1 1 FROM pauth.auth_detail WHERE acct_id = CAST(:acct AS CHAR(11)) AND "
                + condition), acctId, from).query(Integer.class).optional().isPresent();
    }

    /** The rows beyond a position in list order; {@code op} comes from this class only. */
    private static String beyond(String op, boolean inclusive) {
        return "(processed_at " + op + " CAST(:at AS DATETIME2(3)) OR (processed_at = CAST(:at AS DATETIME2(3))"
                + " AND auth_id " + op + (inclusive ? "=" : "") + " :id))";
    }

    private static StatementSpec bind(StatementSpec spec, String acctId, ListPosition from) {
        spec = spec.param("acct", acctId);
        return from == null ? spec : spec.param("at", from.at()).param("id", from.authId());
    }
}
