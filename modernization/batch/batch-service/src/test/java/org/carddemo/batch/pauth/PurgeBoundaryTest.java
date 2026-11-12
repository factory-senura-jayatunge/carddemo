package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.carddemo.batch.pauth.PauthFixtures.acct;
import static org.carddemo.batch.pauth.PurgePendingAuthorizationsJobTest.BUSINESS;
import static org.carddemo.batch.pauth.PurgePendingAuthorizationsJobTest.EXPIRED;
import static org.carddemo.batch.pauth.PurgePendingAuthorizationsJobTest.FRESH;

import java.math.BigDecimal;
import java.util.Map;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The purge at the widths V504 gives the summary: limits DECIMAL(12,2), pending balances
 * DECIMAL(15,2), running totals DECIMAL(15,2), detail amounts DECIMAL(12,2). The purge subtracts
 * what it deleted with no guard of its own: a total may go below zero, and one that would leave
 * its column's range is refused by the database, which fails the chunk.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurgeBoundaryTest extends PurgeTestBase {

    static final String LIMIT_MAX = "9999999999.99";
    static final String TOTAL_MAX = "9999999999999.99";
    static final String AMOUNT_MAX = "9999999999.99";

    private static void assertRow(Map<String, Object> row, String credit, String cash, int approvedCnt,
            String approvedAmt, int declinedCnt, String declinedAmt) {
        assertThat((BigDecimal) row.get("credit_balance")).as("credit_balance").isEqualByComparingTo(credit);
        assertThat((BigDecimal) row.get("cash_balance")).as("cash_balance").isEqualByComparingTo(cash);
        assertThat(row.get("approved_auth_cnt")).as("approved_auth_cnt").isEqualTo(approvedCnt);
        assertThat((BigDecimal) row.get("approved_auth_amt")).as("approved_auth_amt").isEqualByComparingTo(approvedAmt);
        assertThat(row.get("declined_auth_cnt")).as("declined_auth_cnt").isEqualTo(declinedCnt);
        assertThat((BigDecimal) row.get("declined_auth_amt")).as("declined_auth_amt").isEqualByComparingTo(declinedAmt);
    }

    private BigDecimal limit(String acctId, String column) {
        return DB.admin().sql("SELECT " + column + " FROM pauth.auth_summary WHERE acct_id = :a").param("a", acctId)
                .query(BigDecimal.class).single();
    }

    @Test
    @SpecItem({"BR-AUPG-004", "DEF-AUPG-002"})
    void theWidestLimitsBalancesAndAmountsArePurgedExactly() throws Exception {
        String a = acct(1);
        pauth.summary(a, LIMIT_MAX, LIMIT_MAX, TOTAL_MAX, TOTAL_MAX, 2, TOTAL_MAX, 1, TOTAL_MAX);
        pauth.detail(a, EXPIRED, "00", "P", " ", AMOUNT_MAX, AMOUNT_MAX);
        pauth.detail(a, EXPIRED, "05", "P", " ", "0.00", AMOUNT_MAX);
        pauth.detail(a, FRESH, "00", "P", " ", AMOUNT_MAX, AMOUNT_MAX);

        JobExecution execution = purge(BUSINESS, 5, 10);

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertRow(pauth.summaryRow(a), "9990000000000.00", TOTAL_MAX, 1, "9990000000000.00", 0, "9990000000000.00");
        assertThat(limit(a, "credit_limit")).isEqualByComparingTo(LIMIT_MAX);
        assertThat(limit(a, "cash_limit")).isEqualByComparingTo(LIMIT_MAX);
        assertThat(pauth.details(a)).isEqualTo(1);
        assertThat(new BigDecimal(totals(execution).getString(PurgeSummaryWriter.CREDIT_RELEASED)))
                .isEqualByComparingTo(AMOUNT_MAX);
    }

    @Test
    @SpecItem({"BR-AUPG-004", "DEF-AUPG-002"})
    void totalsGoBelowZeroWithoutAGuard() throws Exception {
        String a = acct(2);
        pauth.summary(a, "0.00", 0, "0.00", 0, "0.00");
        pauth.detail(a, EXPIRED, "00", "P", " ", AMOUNT_MAX, AMOUNT_MAX);
        pauth.detail(a, EXPIRED, "05", "P", " ", "0.00", AMOUNT_MAX);
        pauth.detail(a, FRESH, "00", "P", " ", "1.00", "1.00");

        JobExecution execution = purge(BUSINESS, 5, 10);

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertRow(pauth.summaryRow(a), "-" + AMOUNT_MAX, "0.00", -1, "-" + AMOUNT_MAX, -1, "-" + AMOUNT_MAX);
    }

    @Test
    void aTotalThatWouldLeaveDecimal15FailsTheChunkAndRollsItBack() throws Exception {
        String a = acct(3);
        pauth.summary(a, LIMIT_MAX, LIMIT_MAX, "-" + TOTAL_MAX, "0.00", 1, "1.00", 0, "0.00");
        pauth.detail(a, EXPIRED, "00", "P", " ", "1.00", "1.00");
        pauth.detail(a, FRESH, "00", "P", " ", "1.00", "1.00");

        JobExecution execution = purge(BUSINESS, 5, 10);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution)).contains("(SQL Server error 8115)").doesNotContain(a);
        assertThat(pauth.details(a)).isEqualTo(2);
        assertRow(pauth.summaryRow(a), "-" + TOTAL_MAX, "0.00", 1, "1.00", 0, "0.00");
    }
}
