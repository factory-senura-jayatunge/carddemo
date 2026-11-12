package org.carddemo.batch.cycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * postDailyTransactionsJob's {@code snapshotBalances}: the account and category-balance state the
 * cycle's posting starts from, which cycle-rerun restores (V412). The snapshot is taken once per
 * cycle, in the transaction that sets {@code batch.cycle.snapshot_at}: a restart, or a later attempt
 * after cycle-rerun restored it, finds the mark and keeps the first snapshot, so every attempt
 * starts from the same balances and FILLERs. The job runs under the batch window, so no online
 * writer changes a balance between the snapshot and the posting. The highest tran_pk at that
 * moment (V416) tells cycle-rerun whether anything outside the cycle was written since.
 */
final class SnapshotBalancesTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBalancesTasklet.class);

    private final JdbcClient db;

    SnapshotBalancesTasklet(JdbcClient db) {
        this.db = db;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        int marked = db.sql("""
                UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME(),
                    snapshot_tran_pk = (SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction])
                WHERE cycle_id = :id AND snapshot_at IS NULL""").param("id", run.cycleId()).update();
        if (marked == 0) {
            if (db.sql("SELECT COUNT(*) FROM batch.cycle WHERE cycle_id = :id").param("id", run.cycleId())
                    .query(Integer.class).single() == 0) {
                throw new CycleJobException("CYCLE_NOT_FOUND", "cycle " + run.cycleId() + " does not exist");
            }
            log.info("snapshotBalances: cycle {} keeps the snapshot taken before its first posting", run.cycleId());
            return RepeatStatus.FINISHED;
        }
        int accounts = db.sql("""
                INSERT INTO batch.snapshot_account (cycle_id, acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit)
                SELECT :id, acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit FROM core.account""")
                .param("id", run.cycleId()).update();
        int categories = db.sql("""
                INSERT INTO batch.snapshot_tcat_bal (cycle_id, acct_id, type_cd, cat_cd, balance, legacy_filler)
                SELECT :id, acct_id, type_cd, cat_cd, balance, legacy_filler FROM core.tcat_bal""")
                .param("id", run.cycleId()).update();
        contribution.incrementWriteCount(accounts + categories);
        log.info("snapshotBalances: cycle {} kept {} accounts and {} category balances", run.cycleId(), accounts,
                categories);
        return RepeatStatus.FINISHED;
    }
}
