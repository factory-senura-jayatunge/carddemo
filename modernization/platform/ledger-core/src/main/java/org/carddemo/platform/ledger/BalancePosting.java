package org.carddemo.platform.ledger;

import java.math.BigDecimal;

/**
 * Changes account and category balances; the only code that does. Requires an active
 * transaction. Sums are cut to the column's integer digits as COBOL MOVE does (NUM-POST-003);
 * no method runs posting's validations (over-limit, expiry): callers do their own.
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
     * inserting the row, with no {@code legacy_filler}, when missing; {@code curr_bal += amount}; {@code curr_cyc_credit += amount}
     * when {@code amount >= 0}, else {@code curr_cyc_debit += amount}.
     *
     * @throws AccountNotFoundException when no account has {@code acctId}
     */
    void post(String acctId, String typeCd, String catCd, BigDecimal amount);

    /**
     * {@link #post(String, String, String, BigDecimal)} for the cycle's posting, which needs the
     * category record's FILLER (DEF-POST-010): a missing row is inserted with
     * {@code fillerForNewRow} as its {@code legacy_filler}, an existing row keeps its own. The
     * account is found and locked before anything is written.
     *
     * @param fillerForNewRow 22 characters, U+0000 to U+00FF; the caller derives it from the
     *     legacy record area, there is no default
     * @return whether the row was created, and its {@code legacy_filler}
     * @throws AccountNotFoundException when no account has {@code acctId}
     * @throws IllegalArgumentException when {@code fillerForNewRow} is not a FILLER, before any read
     */
    PostedCategory post(String acctId, String typeCd, String catCd, BigDecimal amount, String fillerForNewRow);

    /**
     * The interest calculation's account update (BR-INTC-006): {@code curr_bal += totalInterest};
     * {@code curr_cyc_credit := 0}; {@code curr_cyc_debit := 0}. The cycle totals are reset also
     * when {@code totalInterest} is zero. Category balances are not changed.
     *
     * @param totalInterest the account's {@code WS-TOTAL-INT}: the caller truncates each monthly
     *     interest to cents and cuts the running total to {@code S9(09)V99} on each addition, as
     *     COBOL does; a value with more than 2 decimals is refused, because the column would round it
     * @throws AccountNotFoundException when no account has {@code acctId}
     * @throws IllegalArgumentException when {@code totalInterest} has more than 2 decimals, before any read
     */
    void interest(String acctId, BigDecimal totalInterest);
}
