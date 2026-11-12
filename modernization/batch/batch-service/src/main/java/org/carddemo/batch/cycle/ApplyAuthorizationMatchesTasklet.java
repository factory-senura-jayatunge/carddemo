package org.carddemo.batch.cycle;

import java.math.BigDecimal;
import java.util.List;

import org.carddemo.batch.execution.SafeTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * postDailyTransactionsJob's {@code applyAuthorizationMatches} (D-33): the staged pairs of the
 * attempt in {@code auth_id} order, {@code chunkSize} per fenced transaction, each under a
 * savepoint. A pair marks its approval matched, if it is still pending and not marked as fraud
 * (else {@code changed}), and releases the approved amount from the account's summary, if the
 * summary holds it (else the pair is rolled back as {@code inconsistent}). The approved count
 * and amount stay until the purge deletes the detail. The context holds the last {@code auth_id}
 * done and commits with the chunk; a pair applied already is no longer pending, so a restart
 * never releases twice.
 */
final class ApplyAuthorizationMatchesTasklet implements Tasklet {

    static final String LAST_AUTH_ID = "apply.lastAuthId";
    static final String APPLIED = "applied";
    static final String CHANGED = "changed";
    static final String INCONSISTENT = "inconsistent";

    private static final Logger log = LoggerFactory.getLogger(ApplyAuthorizationMatchesTasklet.class);

    private static final String NEXT = """
            SELECT TOP (:n) auth_id, tran_pk FROM batch.auth_match_pair
            WHERE cycle_id = :c AND attempt = :a AND auth_id > :after ORDER BY auth_id""";

    private static final String MARK = """
            UPDATE pauth.auth_detail
            SET match_status = 'M', matched_tran_pk = :tran,
                matched_tran_id = (SELECT tran_id FROM core.[transaction] WHERE tran_pk = :tran),
                matched_cycle_id = :c, matched_at = SYSUTCDATETIME()
            WHERE auth_id = :auth AND match_status = 'P' AND auth_fraud <> 'F'""";

    /** Read after the mark rather than by OUTPUT, which SQL Server refuses on a table with triggers. */
    private static final String HOLD = "SELECT acct_id, approved_amt FROM pauth.auth_detail WHERE auth_id = :auth";

    private static final String RELEASE = """
            UPDATE pauth.auth_summary SET credit_balance = credit_balance - :amt
            WHERE acct_id = :acct AND credit_balance >= :amt""";

    private record Hold(String acctId, BigDecimal approvedAmt) {
    }

    /** Rolls a pair back to its savepoint when the summary does not hold the amount. */
    private static final class Inconsistent extends RuntimeException {
        Inconsistent() {
            super(null, null, false, false);
        }
    }

    private enum Outcome { APPLIED, CHANGED, INCONSISTENT }

    private final JdbcClient db;
    private final SafeTransactions savepoint;
    private final int chunkSize;

    ApplyAuthorizationMatchesTasklet(JdbcClient db, PlatformTransactionManager tx, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("carddemo.batch.files.chunk-size must be at least 1");
        }
        this.db = db;
        this.savepoint = new SafeTransactions(tx);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.chunkSize = chunkSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        long after = context.getLong(LAST_AUTH_ID, 0);
        List<long[]> pairs = db.sql(NEXT).param("n", chunkSize).param("c", run.cycleId()).param("a", run.attempt())
                .param("after", after).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).list();
        long applied = context.getLong(APPLIED, 0);
        long changed = context.getLong(CHANGED, 0);
        long inconsistent = context.getLong(INCONSISTENT, 0);
        for (long[] pair : pairs) {
            contribution.incrementReadCount();
            switch (apply(run, pair[0], pair[1])) {
                case APPLIED -> applied++;
                case CHANGED -> changed++;
                case INCONSISTENT -> {
                    inconsistent++;
                    log.warn("applyAuthorizationMatches: cycle {}: the summary of authorization {} does not hold its"
                            + " approved amount; not matched", run.cycleId(), pair[0]);
                }
            }
            after = pair[0];
        }
        contribution.incrementWriteCount(pairs.size());
        context.putLong(LAST_AUTH_ID, after);
        context.putLong(APPLIED, applied);
        context.putLong(CHANGED, changed);
        context.putLong(INCONSISTENT, inconsistent);
        if (pairs.size() < chunkSize) {
            log.info("applyAuthorizationMatches: cycle {} attempt {} released {}, {} changed since staged, {}"
                    + " inconsistent", run.cycleId(), run.attempt(), applied, changed, inconsistent);
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }

    private Outcome apply(CycleRun run, long authId, long tranPk) {
        try {
            return savepoint.execute(status -> {
                int marked = db.sql(MARK).param("tran", tranPk).param("c", run.cycleId()).param("auth", authId)
                        .update();
                if (marked == 0) {
                    return Outcome.CHANGED;
                }
                Hold hold = db.sql(HOLD).param("auth", authId)
                        .query((rs, n) -> new Hold(rs.getString(1), rs.getBigDecimal(2))).single();
                int released = db.sql(RELEASE).param("amt", hold.approvedAmt()).param("acct", hold.acctId()).update();
                if (released == 0) {
                    throw new Inconsistent();
                }
                return Outcome.APPLIED;
            });
        } catch (Inconsistent e) {
            return Outcome.INCONSISTENT;
        }
    }
}
