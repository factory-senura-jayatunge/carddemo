package org.carddemo.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The reads of account view (COACTVWC): plain SELECTs, no lock hints (BR-ACVW-001). */
@Repository
public class AccountViewRepository {

    public record AccountRow(String acctId, String activeStatus, BigDecimal currBal, BigDecimal creditLimit,
                             BigDecimal cashCreditLimit, LocalDate openDate, LocalDate expirationDate,
                             LocalDate reissueDate, BigDecimal currCycCredit, BigDecimal currCycDebit,
                             String groupId) {
    }

    /** {@code cardId} is null when the cross-reference names a card that has no core.card row. */
    public record XrefRow(String cardNum, String custId, Long cardId) {
    }

    public record CustomerRow(String custId, String firstName, String middleName, String lastName,
                              String addrLine1, String addrLine2, String addrLine3, String stateCd,
                              String countryCd, String zip, String phone1, String phone2, String ssn,
                              String govtIssuedId, LocalDate dob, String eftAccountId, String priCardHolderInd,
                              int ficoScore) {
    }

    private final JdbcClient jdbc;

    public AccountViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** addr_zip is not read: the view does not show it (BR-ACVW-003). */
    public Optional<AccountRow> account(String acctId) {
        return jdbc.sql("""
                        SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date,
                            expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, group_id
                        FROM core.account WHERE acct_id = :id""")
                .param("id", acctId)
                .query((rs, n) -> new AccountRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getString(11)))
                .optional();
    }

    /** The cross-reference row of the account's lowest card number (D-61), with that card's ID. */
    public Optional<XrefRow> firstCard(String acctId) {
        return jdbc.sql("""
                        SELECT TOP 1 x.card_num, x.cust_id, c.card_id
                        FROM core.card_xref x LEFT JOIN core.card c ON c.card_num = x.card_num
                        WHERE x.acct_id = :id ORDER BY x.card_num""")
                .param("id", acctId)
                .query((rs, n) -> new XrefRow(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class)))
                .optional();
    }

    public Optional<CustomerRow> customer(String custId) {
        return jdbc.sql("""
                        SELECT cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3,
                            state_cd, country_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob, eft_account_id,
                            pri_card_holder_ind, fico_score
                        FROM core.customer WHERE cust_id = :id""")
                .param("id", custId)
                .query((rs, n) -> new CustomerRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                        rs.getObject(15, LocalDate.class), rs.getString(16), rs.getString(17), rs.getInt(18)))
                .optional();
    }
}
