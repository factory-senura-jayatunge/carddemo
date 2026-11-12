package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Owner binding of statement runs (batch.md, "Runs, restart and rerun"): only the execution a run
 * row names may change it, a restart claims it by compare-and-set on the status and the owner it
 * read, and a step of any other execution fails with {@code STATEMENT_RUN_NOT_OWNED} and changes
 * nothing. The runs are changed through batch_svc, as statementJob changes them.
 */
class StatementOwnerBindingTest {

    static final TestDatabase DB = TestDatabase.shared();

    private static final long[] EXECUTIONS = {-41, -42, -43, -44, -45, -46, -47, -48, -50};
    private static final LocalDate FIRST_DATE = LocalDate.of(2101, 9, 1);
    private static final int DATES = 5;

    private final JdbcClient service = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
    private final StatementRuns runs = new StatementRuns(service);

    @BeforeEach
    @AfterEach
    void clean() {
        StatementRunFixtures.drop(DB.admin(), EXECUTIONS);
        DB.admin().sql("DELETE FROM batch.cycle WHERE business_date BETWEEN :from AND :to")
                .param("from", FIRST_DATE).param("to", FIRST_DATE.plusDays(DATES - 1)).update();
    }

    private static StatementCycle cycle(int day) {
        LocalDate date = FIRST_DATE.plusDays(day);
        long id = DB.admin().sql("INSERT INTO batch.cycle (business_date) OUTPUT inserted.cycle_id VALUES (:d)")
                .param("d", date).query(Long.class).single();
        int attempt = DB.admin().sql("SELECT attempt FROM batch.cycle WHERE cycle_id = :c").param("c", id)
                .query(Integer.class).single();
        return new StatementCycle(id, date, attempt);
    }

    private static Map<String, Object> row(long runId) {
        return DB.admin().sql("SELECT status, job_execution_id, failure_code FROM batch.statement_run"
                + " WHERE statement_run_id = :r").param("r", runId).query().singleRow();
    }

    private static void reown(long runId, long execution) {
        DB.admin().sql("UPDATE batch.statement_run SET job_execution_id = :e WHERE statement_run_id = :r")
                .param("e", execution).param("r", runId).update();
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOfSatisfying(StatementFailure.class, f -> assertThat(f.code()).isEqualTo(code));
    }

    private StatementRuns.Run find(StatementCycle cycle) {
        return runs.find(cycle.cycleId(), cycle.attempt()).orElseThrow();
    }

    /**
     * Two restarts read the same RUNNING row; the first claims it. The second's claim, on what it
     * read, updates nothing and fails, and the row stays with the first. The earlier owner can no
     * longer commit an intent or read the run as its own.
     */
    @Test
    void aClaimOnAStaleReadFailsAndTheRowKeepsTheWinner() {
        StatementCycle cycle = cycle(0);
        long runId = StatementRunFixtures.insert(DB.admin(), cycle.cycleId(), "RUNNING", -41);
        StatementRunFixtures.execution(DB.admin(), -42);
        StatementRunFixtures.execution(DB.admin(), -43);
        StatementRuns.Run stale = find(cycle);
        reown(runId, -42);

        assertCode(catchThrowable(() -> runs.claim(stale, -43)), StatementRuns.NOT_OWNED);
        assertThat(row(runId)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", -42L);

        StatementRuns.Run claimed = runs.claim(find(cycle), -43);
        assertThat(claimed.jobExecutionId()).isEqualTo(-43L);
        assertThat(row(runId)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", -43L);

        assertCode(catchThrowable(() -> runs.owned(cycle, -42, false)), StatementRuns.NOT_OWNED);
        StatementRuns.Run asSeenByLoser = new StatementRuns.Run(claimed.id(), claimed.cycleId(), claimed.attempt(),
                claimed.mode(), claimed.status(), claimed.watermark(), claimed.previousRunId(), claimed.folder(),
                claimed.workFolder(), claimed.manifestSha256(), claimed.counts(), -42);
        assertCode(catchThrowable(() -> runs.publishing(asSeenByLoser, -42, cycle.folder() + ".42", "0".repeat(64),
                new StatementRuns.Counts(0, 0, 0, 0))), StatementRuns.NOT_OWNED);
        assertThat(row(runId)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", -43L);
        assertThat(runs.owned(cycle, -43, false).status()).isEqualTo(StatementRuns.Status.RUNNING);
    }

    /**
     * A FAILED run (its owner was recovered) belongs to no execution until a restart claims it
     * RUNNING; a PUBLISHING run is claimed with its status kept, and only the claimant completes it.
     */
    @Test
    void failedAndPublishingRunsAreClaimedAndOnlyTheClaimantCompletes() {
        StatementCycle failedCycle = cycle(1);
        long failed = StatementRunFixtures.insert(DB.admin(), failedCycle.cycleId(), "FAILED", -44);
        StatementRunFixtures.execution(DB.admin(), -45);
        assertCode(catchThrowable(() -> runs.owned(failedCycle, -44, true)), StatementRuns.NOT_OWNED);
        runs.claim(find(failedCycle), -45);
        assertThat(row(failed)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", -45L)
                .containsEntry("failure_code", null);

        StatementCycle publishingCycle = cycle(2);
        long publishing = StatementRunFixtures.insert(DB.admin(), publishingCycle.cycleId(), "PUBLISHING", -46);
        StatementRunFixtures.execution(DB.admin(), -47);
        StatementRuns.Run before = find(publishingCycle);
        StatementRuns.Run claimed = runs.claim(before, -47);
        assertThat(row(publishing)).containsEntry("status", "PUBLISHING").containsEntry("job_execution_id", -47L);

        assertCode(catchThrowable(() -> runs.completed(before, -46)), StatementRuns.NOT_OWNED);
        assertThat(row(publishing)).containsEntry("status", "PUBLISHING");
        runs.completed(claimed, -47);
        assertThat(row(publishing)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", -47L);
        assertThat(runs.owned(publishingCycle, -99, false).status()).as("a COMPLETED run is read by any execution")
                .isEqualTo(StatementRuns.Status.COMPLETED);
    }

    /** Ended runs are never claimed; a SUPERSEDED or INVALIDATED one is refused with its own code. */
    @Test
    void endedRunsAreNeverClaimed() {
        StatementCycle cycle = cycle(3);
        long runId = StatementRunFixtures.insert(DB.admin(), cycle.cycleId(), "COMPLETED", -48);
        assertThatThrownBy(() -> runs.claim(find(cycle), -48)).isInstanceOf(IllegalArgumentException.class);
        for (String status : new String[] {"SUPERSEDED", "INVALIDATED"}) {
            DB.admin().sql("UPDATE batch.statement_run SET status = :s WHERE statement_run_id = :r")
                    .param("s", status).param("r", runId).update();
            assertCode(catchThrowable(() -> runs.owned(cycle, -48, false)), CaptureTasklet.RUN_INVALIDATED);
            assertThatThrownBy(() -> runs.claim(find(cycle), -48)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * A render step of an execution that does not own the run fails before it reads a row or writes
     * a file (its output roots and publication are not even given), and the run is unchanged. The
     * same holds for a FAILED run: only a claim makes it renderable again. A non-owner deletes no
     * account row of a fixed run either.
     */
    @Test
    void aNonOwnerNeitherRendersNorDeletesAccountRows() throws Exception {
        StatementCycle cycle = cycle(4);
        long runId = StatementRunFixtures.insert(DB.admin(), cycle.cycleId(), "RUNNING", -50);
        LegacyRenderTasklet render = new LegacyRenderTasklet(service, runs, null, null);

        assertCode(catchThrowable(() -> render.execute(contribution(-51, cycle), null)), StatementRuns.NOT_OWNED);
        assertThat(row(runId)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", -50L);

        DB.admin().sql("""
                INSERT INTO batch.statement_capture (statement_run_id, acct_id, curr_bal, credit_limit)
                VALUES (:r, '00000000001', 0, 0)""").param("r", runId).update();
        DB.admin().sql("""
                INSERT INTO batch.account_statement (statement_run_id, acct_id, period_start, period_end,
                    opening_balance, closing_balance, net_amount, interest_amount, adjustment_amount, minimum_payment,
                    credit_limit, tran_count, file_name, file_sha256)
                VALUES (:r, '00000000001', :d, :d, 0, 0, 0, 0, 0, 0, 0, 0, '00000000001.pdf', REPLICATE('0', 64))""")
                .param("r", runId).param("d", cycle.businessDate()).update();
        StatementRuns.Run run = find(cycle);
        assertThat(runs.deleteAccountRows(run, -51)).as("a non-owner's delete").isZero();
        assertThat(runs.deleteAccountRows(run, -50)).as("the owner's delete").isOne();

        DB.admin().sql("UPDATE batch.statement_run SET status = 'FAILED', failure_code = 'OWNER_LOST'"
                + " WHERE statement_run_id = :r").param("r", runId).update();
        assertCode(catchThrowable(() -> render.execute(contribution(-50, cycle), null)), StatementRuns.NOT_OWNED);
        assertThat(row(runId)).containsEntry("status", "FAILED").containsEntry("job_execution_id", -50L);
    }

    private static org.springframework.batch.core.step.StepContribution contribution(long execution,
            StatementCycle cycle) {
        var parameters = new JobParametersBuilder().addLong("cycleId", cycle.cycleId())
                .addLocalDate("businessDate", cycle.businessDate()).addLong("attempt", (long) cycle.attempt())
                .toJobParameters();
        JobExecution job = new JobExecution(execution, new JobInstance(execution, "statementJob"), parameters);
        return new StepExecution(1L, "render", job).createStepContribution();
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }
}
