package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.carddemo.batch.internal.CycleReruns;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A statement run that failed and was then invalidated is never restarted (batch.md, "Tests
 * (statement job)", invalidation): after a real dataLoadJob the restart is refused with
 * {@code STATEMENT_RUN_INVALIDATED}; after {@code cycle-rerun} the failed runs of the replaced
 * attempt and of a later cycle are INVALIDATED and their restarts are refused the same way, and the
 * cycle's statements come from the new attempt; and a run whose stored {@code W} is above
 * {@code MAX(tran_pk)} is refused with {@code STATEMENT_WATERMARK_INVALID}.
 * The failure is an execution stopped before the intent, through the publication's test hook.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.fidelity.DEF-STMT-005=fixed",
        "carddemo.data-load.reload-allowed=true"})
class StatementInvalidationTest extends StatementJobTestBase {

    private static final Consumer<JobExecution> NOTHING = execution -> { };

    @Autowired
    StatementPublication publication;

    @Autowired
    CycleReruns reruns;

    @AfterEach
    void noHooks() {
        publication.beforeIntent = NOTHING;
    }

    /** statementJob of the cycle, stopped before its intent: the run stays RUNNING with this execution. */
    private JobExecution failedRun(long cycle) {
        publication.beforeIntent = execution -> {
            throw new StatementPublicationJobTest.Crash();
        };
        JobExecution failed = launch("statementJob", cycle);
        publication.beforeIntent = NOTHING;
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runRow(cycle)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", failed.getId());
        return failed;
    }

    private static void assertRefused(JobExecution execution, String code) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).startsWith(code + ": ");
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aDataLoadAfterAFailedRunRefusesItsRestart(FidelityMode mode) throws Exception {
        long cycle = postedSampleCycle();
        JobExecution failed = failedRun(cycle);

        JobExecution load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED");

        JobExecution restart = launch("statementJob", cycle);

        assertRefused(restart, "STATEMENT_RUN_INVALIDATED");
        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", failed.getId())
                .containsEntry("work_folder", null);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertRunMode(cycle, mode);
    }

    /**
     * {@code cycle-rerun} invalidates the failed run of the cycle's first attempt; the next DAG run
     * captures a new run for attempt 2 and publishes it under {@code <cycleId>-2}. The invalidated
     * run is never claimed.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCycleRerunAfterAFailedRunInvalidatesItAndTheNextAttemptPublishesItsOwnRun(FidelityMode mode) {
        long cycle = postedSampleCycle();
        JobExecution failed = failedRun(cycle);
        windows.close(WindowHolder.cycle(cycle), true);

        assertThat(reruns.rerun(cycle).cycle().attempt()).isEqualTo(2);

        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", failed.getId());
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution second = launch("statementJob", cycle);

        assertThat(second.getStatus()).as(second.getExitStatus().getExitDescription()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> run = DB.admin().sql("""
                SELECT status, folder, job_execution_id FROM batch.statement_run WHERE cycle_id = :c AND attempt = 2""")
                .param("c", cycle).query().singleRow();
        assertThat(run).containsEntry("status", "COMPLETED").containsEntry("folder", cycle + "-2")
                .containsEntry("job_execution_id", second.getId());
        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", failed.getId());
        assertThat(names(StatementFolders.statements(root()))).containsExactly(StatementFolders.WORK, cycle + "-2");
        assertRunMode(cycle, mode);
    }

    /**
     * {@code cycle-rerun} also invalidates the failed run of a later cycle that has not started, so
     * {@code LATER_CYCLE_STARTED} does not apply. The restart of either failed run is then refused
     * with {@code STATEMENT_RUN_INVALIDATED} and its {@code job_execution_id} is unchanged: the
     * replaced attempt's through the job operator, since a launch now starts attempt 2, and the
     * later cycle's through a launch in its own window.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCycleRerunInvalidatesTheFailedRunsOfTheCycleAndOfALaterCycleAndTheirRestartsAreRefused(FidelityMode mode)
            throws Exception {
        long cycle = postedSampleCycle();
        JobExecution failed = failedRun(cycle);
        windows.close(WindowHolder.cycle(cycle), true);
        long later = openCycle("2101-06-02");
        JobExecution laterFailed = failedRun(later);
        windows.close(WindowHolder.cycle(later), false);
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM batch.cycle WHERE cycle_id = ? AND snapshot_at IS NULL
                    AND archived_count IS NULL AND daily_file_sha256 IS NULL""", later))
                .as("the later cycle has not started").isOne();

        assertThat(reruns.rerun(cycle).cycle().attempt()).isEqualTo(2);

        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", failed.getId());
        assertThat(runRow(later)).containsEntry("status", "INVALIDATED")
                .containsEntry("job_execution_id", laterFailed.getId());

        JobExecution restart = repository.getJobExecution(jobOperator.restart(failed).getId());

        assertRefused(restart, "STATEMENT_RUN_INVALIDATED");
        assertThat(runRow(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", failed.getId())
                .containsEntry("work_folder", null);

        windows.close(WindowHolder.cycle(cycle), false);
        windows.open(WindowHolder.cycle(later));
        JobExecution laterRestart = launch("statementJob", later);

        assertRefused(laterRestart, "STATEMENT_RUN_INVALIDATED");
        assertThat(runRow(later)).containsEntry("status", "INVALIDATED")
                .containsEntry("job_execution_id", laterFailed.getId()).containsEntry("work_folder", null);
        assertThat(Files.exists(finalFolder(later))).isFalse();
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertRunMode(cycle, mode);
        assertRunMode(later, mode);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aWatermarkAboveTheHighestTransactionRefusesTheRestart(FidelityMode mode) {
        long cycle = postedSampleCycle();
        JobExecution failed = failedRun(cycle);
        DB.admin().sql("""
                UPDATE batch.statement_run SET watermark_tran_pk = (SELECT MAX(tran_pk) + 1 FROM core.[transaction])
                WHERE cycle_id = :c AND attempt = 1""").param("c", cycle).update();

        JobExecution restart = launch("statementJob", cycle);

        assertRefused(restart, "STATEMENT_WATERMARK_INVALID");
        assertThat(runRow(cycle)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", failed.getId())
                .containsEntry("work_folder", null);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertRunMode(cycle, mode);
    }
}
