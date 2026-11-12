package org.carddemo.verify.differential;

import java.util.ArrayList;
import java.util.List;

import org.carddemo.batch.internal.ExecutionOutputs;

/**
 * The counts a job reports in its execution context must agree with the rows it left. A
 * disagreement means the database does not hold one complete run (a partial restart, rows of
 * another attempt, a later job already run), so nothing is exported.
 */
final class CountRules {

    private CountRules() {
    }

    /** What postDailyTransactionsJob reported and what the cycle's rows hold. */
    record Posting(long dailyRows, long rejectRows, long postedRows, long interestRows, long contextPosted,
            long contextRejected, Long jobRejectCount, String exitCode) {
    }

    /** What calculateInterestJob reported and what the rows hold. */
    record Interest(long interestRows, long balanceAccounts, long contextAccounts, long contextTransactions) {
    }

    static List<String> problems(Posting p) {
        List<String> problems = new ArrayList<>();
        if (p.dailyRows() != p.contextPosted() + p.contextRejected()) {
            problems.add("the cycle has " + p.dailyRows() + " daily rows, but step post posted " + p.contextPosted()
                    + " and rejected " + p.contextRejected());
        }
        if (p.rejectRows() != p.contextRejected()) {
            problems.add("the cycle has " + p.rejectRows() + " reject rows, but step post rejected "
                    + p.contextRejected());
        }
        if (p.jobRejectCount() == null) {
            problems.add("postDailyTransactionsJob has no reject count; step exportRejects did not complete");
        } else if (p.jobRejectCount() != p.rejectRows()) {
            problems.add("step exportRejects wrote " + p.jobRejectCount() + " rejects, but the cycle has "
                    + p.rejectRows() + " reject rows");
        }
        if (p.postedRows() != p.contextPosted()) {
            problems.add("the cycle has " + p.postedRows() + " posted master transactions, but step post posted "
                    + p.contextPosted());
        }
        if (p.interestRows() != 0) {
            problems.add("the cycle already has " + p.interestRows()
                    + " interest transactions; the POSTTRAN export must run before calculateInterestJob");
        }
        String expectedExit = p.rejectRows() > 0 ? ExecutionOutputs.COMPLETED_WITH_REJECTS : "COMPLETED";
        if (!expectedExit.equals(p.exitCode())) {
            problems.add("postDailyTransactionsJob ended " + p.exitCode() + " with " + p.rejectRows()
                    + " rejects; expected " + expectedExit);
        }
        return problems;
    }

    static List<String> problems(Interest i) {
        List<String> problems = new ArrayList<>();
        if (i.interestRows() != i.contextTransactions()) {
            problems.add("the cycle has " + i.interestRows() + " interest transactions, but step interest wrote "
                    + i.contextTransactions());
        }
        if (i.balanceAccounts() != i.contextAccounts()) {
            problems.add("core.tcat_bal has " + i.balanceAccounts() + " accounts, but step interest updated "
                    + i.contextAccounts());
        }
        return problems;
    }

    /** The return code the legacy job ends with: CBTRN02C sets 4 when it rejected a record. */
    static int postingReturnCode(long rejected) {
        return rejected > 0 ? 4 : 0;
    }
}
