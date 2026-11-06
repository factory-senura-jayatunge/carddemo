package org.carddemo.platform.ledger;

import java.math.BigDecimal;

/**
 * Changes account and category balances; the only code that does. Requires an active
 * transaction. Sums are cut to the column's integer digits as COBOL MOVE does (NUM-POST-003);
 * neither method runs posting's validations (over-limit, expiry): callers do their own.
 */
public interface BalancePosting {

    /**
     * {@code curr_bal += delta}, nothing else (Bill Pay legacy mode, BR-BILL-006).
     *
     * @throws AccountNotFoundException when no account has {@code acctId}
     */
    void balanceOnly(String acctId, BigDecimal delta);

    /**
     * The POSTTRAN rule (BR-POST-004, BR-POST-005): {@code tcat_bal(acct, type, cat) += amount},
     * inserting the row when missing; {@code curr_bal += amount}; {@code curr_cyc_credit += amount}
     * when {@code amount >= 0}, else {@code curr_cyc_debit += amount}.
     *
     * @throws AccountNotFoundException when no account has {@code acctId}
     */
    void post(String acctId, String typeCd, String catCd, BigDecimal amount);
}
