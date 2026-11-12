package org.carddemo.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads and writes of account update (COACTUPC). The account is read first, then the
 * cross-reference row of its lowest card, then that row's customer (D-61). The writes name only
 * the columns the screen owns, so the balances, {@code addr_zip}, {@code legacy_filler}, the keys
 * and {@code country_cd} keep their stored bytes (D-08, D-11, BR-ACUP-008).
 */
@Repository
public class AccountUpdateRepository {

    public record StoredAccount(String acctId, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
                                BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate,
                                LocalDate reissueDate, BigDecimal currCycCredit, BigDecimal currCycDebit,
                                String groupId, byte[] rowVersion) {
    }

    /** {@code cardId} is null when the cross-reference names a card that has no core.card row. */
    public record Link(String custId, Long cardId) {
    }

    public record StoredCustomer(String custId, String firstName, String middleName, String lastName,
                                 String addrLine1, String addrLine2, String addrLine3, String stateCd,
                                 String countryCd, String zip, String phone1, String phone2, String ssn,
                                 String govtIssuedId, LocalDate dob, String eftAccountId, String priCardHolderInd,
                                 int ficoScore, byte[] rowVersion) {
    }

    /** The columns of R__grants_account.sql's UPDATE grants: nothing else is ever written. */
    static final Set<String> ACCOUNT_COLUMNS = Set.of("active_status", "credit_limit", "cash_credit_limit",
            "open_date", "expiration_date", "reissue_date", "group_id");
    static final Set<String> CUSTOMER_COLUMNS = Set.of("first_name", "middle_name", "last_name", "addr_line_1",
            "addr_line_2", "addr_line_3", "state_cd", "zip", "phone_1", "phone_2", "ssn", "govt_issued_id", "dob",
            "eft_account_id", "pri_card_holder_ind", "fico_score");

    private static final String ACCOUNT = """
            SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date,
                reissue_date, curr_cyc_credit, curr_cyc_debit, group_id, row_version
            FROM core.account""";

    private static final String CUSTOMER = """
            SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, state_cd,
                country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind,
                fico_score, row_version
            FROM core.customer""";

    private static final RowMapper<StoredAccount> ACCOUNT_ROW = (rs, n) -> new StoredAccount(rs.getString(1),
            rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
            rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
            rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11), rs.getBytes(12));

    private static final RowMapper<StoredCustomer> CUSTOMER_ROW = (rs, n) -> new StoredCustomer(rs.getString(1),
            rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
            rs.getString(14), rs.getObject(15, LocalDate.class), rs.getString(16), rs.getString(17), rs.getInt(18),
            rs.getBytes(19));

    private final JdbcClient jdbc;

    public AccountUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredAccount> account(String acctId) {
        return jdbc.sql(ACCOUNT + " WHERE acct_id = :id").param("id", acctId).query(ACCOUNT_ROW).optional();
    }

    /** SE-ACUP-004: the save's read, holding an update lock until the transaction ends. */
    public Optional<StoredAccount> lockAccount(String acctId) {
        return jdbc.sql(ACCOUNT + " WITH (UPDLOCK, ROWLOCK) WHERE acct_id = :id").param("id", acctId)
                .query(ACCOUNT_ROW).optional();
    }

    /** The cross-reference row of the account's lowest card number (D-61), with that card's ID. */
    public Optional<Link> firstCard(String acctId) {
        return jdbc.sql("""
                        SELECT TOP 1 x.cust_id, c.card_id
                        FROM core.card_xref x LEFT JOIN core.card c ON c.card_num = x.card_num
                        WHERE x.acct_id = :id ORDER BY x.card_num""")
                .param("id", acctId)
                .query((rs, n) -> new Link(rs.getString(1), rs.getObject(2, Long.class)))
                .optional();
    }

    public Optional<StoredCustomer> customer(String custId) {
        return jdbc.sql(CUSTOMER + " WHERE cust_id = :id").param("id", custId).query(CUSTOMER_ROW).optional();
    }

    /** SE-ACUP-005: the customer named by the cross-reference, locked as the account is. */
    public Optional<StoredCustomer> lockCustomer(String custId) {
        return jdbc.sql(CUSTOMER + " WITH (UPDLOCK, ROWLOCK) WHERE cust_id = :id").param("id", custId)
                .query(CUSTOMER_ROW).optional();
    }

    /** SE-ACUP-006: the changed account columns, only while the row has the version the save read. */
    public int updateAccount(String acctId, byte[] rowVersion, Map<String, Object> columns) {
        return update("core.account", "acct_id", ACCOUNT_COLUMNS, acctId, rowVersion, columns);
    }

    /** SE-ACUP-007: the changed customer columns, in the account's transaction (D-16). */
    public int updateCustomer(String custId, byte[] rowVersion, Map<String, Object> columns) {
        return update("core.customer", "cust_id", CUSTOMER_COLUMNS, custId, rowVersion, columns);
    }

    private int update(String table, String key, Set<String> allowed, String id, byte[] rowVersion,
            Map<String, Object> columns) {
        if (columns.isEmpty() || !allowed.containsAll(columns.keySet())) {
            throw new IllegalArgumentException(table + " columns " + columns.keySet());
        }
        String set = columns.keySet().stream().map(c -> c + " = :" + c).collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbc.sql("UPDATE " + table + " SET " + set + " WHERE " + key
                + " = :key AND row_version = :version");
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            statement = statement.param(column.getKey(), column.getValue());
        }
        return statement.param("key", id).param("version", rowVersion).update();
    }
}
