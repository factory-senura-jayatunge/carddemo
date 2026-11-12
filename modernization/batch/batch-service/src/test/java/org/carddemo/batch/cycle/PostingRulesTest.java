package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.carddemo.batch.cycle.PostingRules.Account;
import org.carddemo.batch.cycle.PostingRules.Reject;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

    private static final String ORIG_TS = "2022-07-06 10:15:30.000000";

    private static Account account(String credit, String debit, String limit, String expiry) {
        return new Account(new BigDecimal(credit), new BigDecimal(debit), new BigDecimal(limit), expiry);
    }

    private static Optional<Reject> check(Account account, String amount, String origTs) {
        return PostingRules.check(true, account, new BigDecimal(amount), origTs);
    }

    @Test
    void anUnknownCardIsRejectedBeforeTheAccountIsLookedAt() {
        assertThat(PostingRules.check(false, null, BigDecimal.ONE, ORIG_TS))
                .contains(new Reject("0100", "0100"));
        assertThat(PostingRules.check(false, account("0", "0", "0", "2000-01-01"), BigDecimal.TEN, ORIG_TS))
                .contains(new Reject("0100", "0100"));
    }

    @Test
    void aCardWhoseAccountIsMissingIsRejectedWith101() {
        assertThat(PostingRules.check(true, null, BigDecimal.ONE, ORIG_TS)).contains(new Reject("0101", "0101"));
    }

    @Test
    void theCycleBalancePlusTheAmountMayReachTheLimitButNotPassIt() {
        Account acct = account("900.00", "100.00", "1000.00", "2030-12-31");
        assertThat(check(acct, "200.00", ORIG_TS)).isEmpty();
        assertThat(check(acct, "200.01", ORIG_TS)).contains(new Reject("0102", "0102"));
    }

    @Test
    void theLimitCheckUsesTheLegacyCycleFormulaNotTheCurrentBalance() {
        // DEF-POST-005 kept: credits this cycle count against the limit, debits free it up.
        Account acct = account("0.00", "5000.00", "1000.00", "2030-12-31");
        assertThat(check(acct, "5900.00", ORIG_TS)).isEmpty();
        assertThat(check(account("1000.00", "0.00", "1000.00", "2030-12-31"), "-0.01", ORIG_TS)).isEmpty();
        assertThat(check(account("1000.00", "0.00", "1000.00", "2030-12-31"), "0.01", ORIG_TS))
                .contains(new Reject("0102", "0102"));
    }

    @Test
    void aTransactionOnTheExpirationDayIsStillAccepted() {
        assertThat(check(account("0", "0", "1000", "2022-07-06"), "1.00", ORIG_TS)).isEmpty();
        assertThat(check(account("0", "0", "1000", "2022-07-05"), "1.00", ORIG_TS))
                .contains(new Reject("0103", "0103"));
    }

    @Test
    void theExpirationCheckComparesTextNotDates() {
        // A blank origination time sorts below every digit, so it never counts as late.
        assertThat(check(account("0", "0", "1000", "2000-01-01"), "1.00", "")).isEmpty();
        assertThat(check(account("0", "0", "1000", "2000-01-01"), "1.00", null)).isEmpty();
        // A blank expiration date sorts below any origination date.
        assertThat(check(account("0", "0", "1000", ""), "1.00", ORIG_TS)).contains(new Reject("0103", "0103"));
        // Only the first ten characters of the timestamp take part.
        assertThat(check(account("0", "0", "1000", "2022-07-06"), "1.00", "2022-07-06Z")).isEmpty();
        assertThat(PostingRules.compareAsText("2022-07-06", "2022-07-06 ")).isZero();
    }

    @Test
    void bothFailuresKeepTheLastAsTheReasonAndListBoth() {
        assertThat(check(account("1000.00", "0", "1000.00", "2022-01-01"), "5.00", ORIG_TS))
                .contains(new Reject("0103", "0102,0103"));
    }

    @Test
    void everyReasonHasTheLegacyText() {
        assertThat(PostingRules.description("0100")).isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(PostingRules.description("0101")).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(PostingRules.description("0102")).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(PostingRules.description("0103")).isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(PostingRules.description("0109")).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(new Reject("0103", "0102,0103").description())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThatThrownBy(() -> PostingRules.description("0104")).isInstanceOf(IllegalArgumentException.class);
    }
}
