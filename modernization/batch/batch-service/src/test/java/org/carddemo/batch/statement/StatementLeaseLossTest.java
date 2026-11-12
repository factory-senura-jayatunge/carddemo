package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * statementJob's publication when owner A loses its lease (batch.md, "Tests (statement job)",
 * publication and lease loss; contract section 4.7 lease settings: interval 0.5 s, TTL 2 s, grace
 * 2 s, as in DailyFileLeaseLossTest). A is paused at the start of a publication step through the
 * publication's test hook, its renewals are suspended until its lease is recoverable, and owner B
 * restarts the instance. Whatever A does when it resumes, it commits no status, links nothing that
 * B's final folder does not already hold, and replaces no file.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.fidelity.DEF-STMT-005=fixed",
        "carddemo.batch.async-launch=true",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
class StatementLeaseLossTest extends StatementJobTestBase {

    private static final Consumer<JobExecution> NOTHING = execution -> { };

    @Autowired
    StatementPublication publication;

    @Autowired
    LeaseKeeper keeper;

    @Autowired
    ExecutionLeases leases;

    @Autowired
    ExecutionRecovery recovery;

    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final AtomicLong pausedExecution = new AtomicLong();
    /** Every execution a test started, so that none still runs when the next test empties the tables. */
    final List<JobExecution> started = new CopyOnWriteArrayList<>();

    /**
     * Pauses the first execution that reaches the hook, after {@code before} ran for it, until the
     * test resumes it; every later execution passes.
     */
    private Consumer<JobExecution> pauseTheFirst(Consumer<JobExecution> before) {
        return execution -> {
            if (pausedExecution.compareAndSet(0, execution.getId())) {
                before.accept(execution);
                paused.countDown();
                try {
                    if (!resume.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the paused owner was never resumed");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** Releases a paused owner and waits, bounded, until every started execution has ended and its lease is no longer kept. */
    @AfterEach
    void resumeEveryOwnerAndAwaitItsEnd() throws InterruptedException {
        resume.countDown();
        keeper.suspendRenewals(false);
        publication.beforeIntent = NOTHING;
        publication.beforePublish = NOTHING;
        publication.beforeComplete = NOTHING;
        AssertionError failure = null;
        for (JobExecution execution : started) {
            try {
                await("execution " + execution.getId() + " ends after the test", Duration.ofSeconds(60),
                        () -> execution.getEndTime() != null && !keeper.owns(execution.getId()));
            } catch (AssertionError e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        started.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Lease lost before the intent: A is paused after its render, recovered by B's launch, and B
     * renders and publishes. A resumes: it commits no PUBLISHING and links nothing, B's final folder
     * keeps its files, and the published folder is B's working folder, never A's.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredBeforeItsIntentPublishesNothingAfterTheNextOwnerPublished(FidelityMode mode)
            throws Exception {
        long cycle = postedSampleCycle();
        publication.beforeIntent = pauseTheFirst(execution -> assertThat(names(workFolder(cycle, execution.getId())))
                .isNotEmpty());
        JobExecution a = start(cycle);
        awaitPaused(a);
        assertThat(runRow(cycle)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", a.getId());
        loseTheLease(a);

        JobExecution b = start(cycle);
        keeper.suspendRenewals(false);
        awaitEnd(b);

        assertCompletedBy(b, cycle);
        assertThat(runRow(cycle)).containsEntry("work_folder", cycle + "-1." + b.getId());
        SortedMap<String, FileState> published = states(finalFolder(cycle));
        Map<String, Map<String, Object>> rows = accountRows(runId(cycle));

        resume.countDown();
        awaitEnd(a);

        assertLost(a, LeaseLostException.CODE);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", b.getId()).containsEntry("work_folder", cycle + "-1." + b.getId());
        assertThat(states(finalFolder(cycle))).isEqualTo(published);
        assertThat(accountRows(runId(cycle))).isEqualTo(rows);
        assertOneFinalFolder(cycle);
    }

    /**
     * The same loss with A resumed first: A is recovered directly and resumes before any other
     * owner claimed the run. Its intent finds the run FAILED, no longer its own, and fails with
     * {@code STATEMENT_RUN_NOT_OWNED} before any commit, so the run stays FAILED without a working
     * folder; B's restart then publishes its own render.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredBeforeItsIntentCommitsNothingWhenItResumesFirst(FidelityMode mode) throws Exception {
        long cycle = postedSampleCycle();
        publication.beforeIntent = pauseTheFirst(execution -> assertThat(names(workFolder(cycle, execution.getId())))
                .isNotEmpty());
        JobExecution a = start(cycle);
        awaitPaused(a);
        loseTheLease(a);
        assertThat(recovery.recover(a.getId())).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
        assertThat(runRow(cycle)).containsEntry("status", "FAILED").containsEntry("failure_code", "OWNER_LOST");

        resume.countDown();
        awaitEnd(a);
        keeper.suspendRenewals(false);

        assertLost(a, StatementRuns.NOT_OWNED);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "FAILED")
                .containsEntry("work_folder", null)
                .containsEntry("manifest_sha256", null);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();

        JobExecution b = start(cycle);
        awaitEnd(b);

        assertCompletedBy(b, cycle);
        assertThat(runRow(cycle)).containsEntry("work_folder", cycle + "-1." + b.getId());
        assertOneFinalFolder(cycle);
    }

    /**
     * Lease lost after the intent: A committed PUBLISHING and is paused before its first link; B
     * finishes the publication of A's committed folder. A resumes, finds the run COMPLETED, only
     * verifies, and its step's commit fails its fence. The final folder is unchanged.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredAfterItsIntentLeavesTheNextOwnersPublicationUnchanged(FidelityMode mode) throws Exception {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> committed = new AtomicReference<>();
        publication.beforePublish = pauseTheFirst(execution -> committed.set(hashes(workFolder(cycle, execution.getId()))));
        JobExecution a = start(cycle);
        awaitPaused(a);
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING").containsEntry("job_execution_id", a.getId())
                .containsEntry("work_folder", cycle + "-1." + a.getId());
        loseTheLease(a);

        JobExecution b = start(cycle);
        keeper.suspendRenewals(false);
        awaitEnd(b);

        assertCompletedBy(b, cycle);
        assertThat(runRow(cycle)).containsEntry("work_folder", cycle + "-1." + a.getId());
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(committed.get()));
        SortedMap<String, FileState> published = states(finalFolder(cycle));
        Map<String, Map<String, Object>> rows = accountRows(runId(cycle));

        resume.countDown();
        awaitEnd(a);

        assertLost(a, LeaseLostException.CODE);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", b.getId());
        assertThat(states(finalFolder(cycle))).isEqualTo(published);
        assertThat(accountRows(runId(cycle))).isEqualTo(rows);
        assertOneFinalFolder(cycle);
    }

    /**
     * A paused after some of its links: at the pause the final folder holds what A's publication
     * links first (the folder, {@code .claim} from A's committed manifest and the first ten files),
     * made here as contract section 4.8 orders them. B joins the folder and completes it; no file
     * A linked is replaced, and A changes nothing when it resumes.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredAfterSomeLinksHasNoLinkedFileReplaced(FidelityMode mode) throws Exception {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> committed = new AtomicReference<>();
        AtomicReference<SortedMap<String, FileState>> linked = new AtomicReference<>();
        publication.beforePublish = pauseTheFirst(execution -> {
            Path work = workFolder(cycle, execution.getId());
            committed.set(hashes(work));
            Path target = finalFolder(cycle);
            try {
                Files.createDirectory(target, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                Files.createLink(target.resolve(FolderPublication.CLAIM), work.resolve(FolderPublication.MANIFEST));
                List<String> files = new ArrayList<>(committed.get().keySet());
                files.remove(FolderPublication.MANIFEST);
                for (String name : files.subList(0, 10)) {
                    Files.createLink(target.resolve(name), work.resolve(name));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            linked.set(states(target));
        });
        JobExecution a = start(cycle);
        awaitPaused(a);
        loseTheLease(a);

        JobExecution b = start(cycle);
        keeper.suspendRenewals(false);
        awaitEnd(b);

        assertCompletedBy(b, cycle);
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(committed.get()));
        SortedMap<String, FileState> published = states(finalFolder(cycle));
        linked.get().forEach((name, state) -> assertThat(published.get(name)).as(name).isEqualTo(state));

        resume.countDown();
        awaitEnd(a);

        assertLost(a, LeaseLostException.CODE);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", b.getId());
        assertThat(states(finalFolder(cycle))).isEqualTo(published);
        assertOneFinalFolder(cycle);
    }

    // ---- helpers ----

    /** The execution the launch runs, as the running thread updates it. */
    private JobExecution start(long cycle) {
        JobExecution execution = launches.launch("statementJob", Map.of("params", Map.of("cycleId", cycle)))
                .execution();
        started.add(execution);
        return execution;
    }

    private void awaitPaused(JobExecution a) throws InterruptedException {
        assertThat(paused.await(60, TimeUnit.SECONDS)).as("A reaches the pause").isTrue();
        assertThat(pausedExecution.get()).isEqualTo(a.getId());
    }

    /** A's renewals stop until its lease is recoverable; the caller resumes them. */
    private void loseTheLease(JobExecution a) throws InterruptedException {
        keeper.suspendRenewals(true);
        await("A's lease is recoverable", Duration.ofSeconds(10), () -> leases.recoverable(a.getId()));
    }

    private void assertCompletedBy(JobExecution execution, long cycle) {
        JobExecution stored = repository.getJobExecution(execution.getId());
        assertThat(stored.getStatus()).as(stored.getExitStatus().getExitDescription()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", execution.getId());
    }

    /**
     * A's process sees its lease lost, the repository keeps the recovery's FAILED and OWNER_LOST,
     * and A's own execution, which its thread could no longer store, failed with {@code ownFailure}.
     */
    private void assertLost(JobExecution a, String ownFailure) throws InterruptedException {
        await("A's process sees its lease lost", Duration.ofSeconds(5), () -> keeper.isLost(a.getId()));
        Map<String, Object> row = DB.admin().sql("SELECT STATUS, EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION "
                + "WHERE JOB_EXECUTION_ID = :id").param("id", a.getId()).query().singleRow();
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat((String) row.get("EXIT_MESSAGE")).startsWith("OWNER_LOST");
        assertThat(a.getStatus()).isNotEqualTo(BatchStatus.COMPLETED);
        assertThat(failureCodes(a)).as("A's own failures").contains(ownFailure);
    }

    /** The codes of the lease losses and statement failures in an execution's failures and their causes. */
    private static List<String> failureCodes(JobExecution execution) {
        List<String> codes = new ArrayList<>();
        for (Throwable failure : execution.getAllFailureExceptions()) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof LeaseLostException) {
                    codes.add(LeaseLostException.CODE);
                } else if (t instanceof StatementFailure f) {
                    codes.add(f.code());
                }
            }
        }
        return codes;
    }

    private static void awaitEnd(JobExecution execution) throws InterruptedException {
        await("execution " + execution.getId() + " ends", Duration.ofSeconds(120), () -> execution.getEndTime() != null);
        Thread.sleep(200);
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(50);
        }
    }
}
