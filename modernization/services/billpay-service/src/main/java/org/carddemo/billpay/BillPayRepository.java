package org.carddemo.billpay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bill Pay's own reads and its payment record. Transactions and balances go through ledger-core. */
@Repository
public class BillPayRepository {

    private final JdbcClient jdbc;

    public BillPayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The preview read, without a lock. */
    public Optional<BigDecimal> balance(String acctId) {
        if (tooLong(acctId)) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT curr_bal FROM core.account WHERE acct_id = :id")
                .param("id", acctId).query(BigDecimal.class).optional();
    }

    /** The payment read: the update lock makes a second payment wait and then see the new balance (BR-BILL-001). */
    public Optional<BigDecimal> lockBalance(String acctId) {
        if (tooLong(acctId)) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT curr_bal FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = :id")
                .param("id", acctId).query(BigDecimal.class).optional();
    }

    /** The lowest card number of the account (BR-BILL-005, DEF-BILL-002). */
    public Optional<String> firstCard(String acctId) {
        return jdbc.sql("SELECT TOP 1 card_num FROM core.card_xref WHERE acct_id = :id ORDER BY card_num")
                .param("id", acctId).query(String.class).optional();
    }

    /**
     * SQL Server ignores trailing spaces when it compares CHAR values, so {@code "00000000001 "}
     * would find account {@code 00000000001}; the legacy 11-byte key cannot hold a 12th character.
     */
    private static boolean tooLong(String acctId) {
        return acctId.length() > BillPayService.ACCT_ID_LENGTH;
    }

    public record StoredPayment(String tranId, String acctId, String paidBy, BigDecimal amount) {
    }

    public Optional<StoredPayment> findPayment(UUID idempotencyKey) {
        return jdbc.sql("""
                        SELECT tran_id, acct_id, paid_by, amount FROM billpay.payment
                        WHERE idempotency_key = CAST(:key AS UNIQUEIDENTIFIER)""")
                .param("key", idempotencyKey.toString())
                .query((rs, n) -> new StoredPayment(rs.getString(1), rs.getString(2).trim(), rs.getString(3).trim(),
                        rs.getBigDecimal(4)))
                .optional();
    }

    public void insertPayment(long tranPk, String tranId, String acctId, String paidBy, UUID idempotencyKey,
                              BigDecimal amount, BigDecimal balanceBefore, LocalDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO billpay.payment (tran_pk, tran_id, acct_id, paid_by, idempotency_key, amount,
                            balance_before, created_at)
                        VALUES (:pk, :tranId, :acct, :paidBy, CAST(:key AS UNIQUEIDENTIFIER), :amount, :before, :at)""")
                .param("pk", tranPk).param("tranId", tranId).param("acct", acctId).param("paidBy", paidBy)
                .param("key", idempotencyKey.toString()).param("amount", amount).param("before", balanceBefore)
                .param("at", createdAt)
                .update();
    }
}
