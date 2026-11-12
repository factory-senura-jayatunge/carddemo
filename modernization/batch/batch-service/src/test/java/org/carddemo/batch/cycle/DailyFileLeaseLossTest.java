package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.LeaseKeeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Contract section 4.9: owner A of an attempt's {@code stage} is paused after its archive copy is
 * published and before its identity commit, and its lease is lost there. Whatever the order in
 * which A resumes and the next owner B stages, A commits no identity and no staged row, the
 * inbound file stays the operator's, and the archive copy that the stored identity names is
 * never replaced. Interval 0.5 s, TTL 2 s and grace 2 s, as in ExecutionLeaseTest.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.batch.async-launch=true",
        "carddemo.batch.files.chunk-size=7",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
class DailyFileLeaseLossTest extends CycleJobTestBase {

    static final String DATE = "2101-04-01";

    @Autowired
    DailyFiles files;

    @Autowired
    LeaseKeeper keeper;

    @Autowired
    ExecutionLeases leases;

    @Autowired
    ExecutionRecovery recovery;

    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final AtomicLong pausedExecution = new AtomicLong();
    /** Every execution a test launched, so that none still runs when the next test empties the tables. */
    final List<JobExecution> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void pauseTheFirstOwnerAfterItsCopy() {
        files.afterCopyPublished = execution -> {
            if (pausedExecution.compareAndSet(0, execution.getId())) {
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

    /**
     * Releases a paused owner and waits, bounded, until every launched execution has ended and its
     * lease is no longer kept, whatever the test's outcome. A failure here is thrown after all of
     * it ran; JUnit adds it as suppressed to a failure of the test itself.
     */
    @AfterEach
    void resumeEveryOwnerAndAwaitItsEnd() throws InterruptedException {
        resume.countDown();
        keeper.suspendRenewals(false);
        files.afterCopyPublished = execution -> { };
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

    /** The case the contract lists: A is recovered by B's launch, B stages, then A resumes. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredAfterItsCopyCommitsNoIdentityAfterTheNextOwnerStaged() throws Exception {
        List<String> lines = sampleDaily();
        byte[] content = dailyFile(DATE, lines);
        IngestDailyFileJobTest.Inbound before = IngestDailyFileJobTest.Inbound.of(inbound(DATE));
        long cycle = openCycle(DATE);
        JobExecution a = start(cycle);
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A reaches the point after its copy").isTrue();
        assertThat(pausedExecution.get()).isEqualTo(a.getId());
        Path copy = archived(DATE, 1, content);
        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        assertNoIdentity(cycle);
        keeper.suspendRenewals(true);
        await("A's lease is recoverable", Duration.ofSeconds(10), () -> leases.recoverable(a.getId()));

        JobExecution b = start(cycle);
        keeper.suspendRenewals(false);

        assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(leaseRow(a.getId())).containsEntry("revoked_reason", "OWNER_LOST");
        awaitEnd(b);
        assertThat(repository.getJobExecution(b.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        FileState staged = FileState.of(copy);

        resume.countDown();
        awaitEnd(a);

        assertLost(a);
        assertIdentity(cycle, copy, content);
        assertStaged(cycle, lines);
        assertThat(FileState.of(copy)).as("B's copy is never replaced").isEqualTo(staged);
        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        assertThat(names(archiveFolder())).containsExactly(".work", copy.getFileName().toString());
        assertThat(names(archiveFolder().resolve(".work"))).isEmpty();
        assertThat(IngestDailyFileJobTest.Inbound.of(inbound(DATE))).as("the inbound file is the operator's")
                .isEqualTo(before);
    }

    /**
     * A resumes before any other owner stored an identity, so its compare-and-set matches the row;
     * its transaction, which holds that update and the first chunk, must still not commit. The
     * renewals stay suspended while A resumes, so A's own process has not yet marked it lost.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anOwnerRecoveredAfterItsCopyRollsBackItsIdentityCommitAndTheRestartStagesTheSameCopy() throws Exception {
        List<String> lines = sampleDaily();
        byte[] content = dailyFile(DATE, lines);
        IngestDailyFileJobTest.Inbound before = IngestDailyFileJobTest.Inbound.of(inbound(DATE));
        long cycle = openCycle(DATE);
        JobExecution a = start(cycle);
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A reaches the point after its copy").isTrue();
        keeper.suspendRenewals(true);
        await("A's lease is recoverable", Duration.ofSeconds(10), () -> leases.recoverable(a.getId()));
        assertThat(recovery.recover(a.getId())).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
        assertThat(keeper.isLost(a.getId())).as("not yet seen by A's process").isFalse();

        resume.countDown();
        awaitEnd(a);
        keeper.suspendRenewals(false);

        assertLost(a);
        assertNoIdentity(cycle);
        assertStaged(cycle, List.of());
        Path copy = archived(DATE, 1, content);
        assertThat(Files.readAllBytes(copy)).as("A's copy, published before the pause").isEqualTo(content);
        FileState published = FileState.of(copy);
        assertThat(names(archiveFolder().resolve(".work"))).isEmpty();

        JobExecution b = start(cycle);
        awaitEnd(b);

        assertThat(repository.getJobExecution(b.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertIdentity(cycle, copy, content);
        assertStaged(cycle, lines);
        assertThat(FileState.of(copy)).as("B finds the equal copy and replaces nothing").isEqualTo(published);
        assertThat(names(archiveFolder())).containsExactly(".work", copy.getFileName().toString());
        assertThat(names(archiveFolder().resolve(".work"))).isEmpty();
        assertThat(IngestDailyFileJobTest.Inbound.of(inbound(DATE))).isEqualTo(before);
    }

    /** The execution the launch runs, as the running thread updates it. */
    private JobExecution start(long cycle) {
        JobExecution execution = launches.launch("ingestDailyFileJob", Map.of("params", Map.of("cycleId", cycle)))
                .execution();
        started.add(execution);
        return execution;
    }

    private void assertLost(JobExecution a) throws InterruptedException {
        await("A's process sees its lease lost", Duration.ofSeconds(5), () -> keeper.isLost(a.getId()));
        Map<String, Object> row = DB.admin().sql("SELECT STATUS, EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION "
                + "WHERE JOB_EXECUTION_ID = :id").param("id", a.getId()).query().singleRow();
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat((String) row.get("EXIT_MESSAGE")).startsWith("OWNER_LOST");
        assertThat(DB.admin().sql("SELECT STATUS FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", a.getId()).query(String.class).list()).containsOnly("FAILED");
    }

    private static void assertNoIdentity(long cycle) {
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.cycle WHERE cycle_id = ? AND daily_file_name IS NULL "
                + "AND daily_file_sha256 IS NULL", cycle)).isEqualTo(1);
    }

    private static void assertIdentity(long cycle, Path copy, byte[] content) {
        Map<String, Object> row = DB.admin().sql("SELECT daily_file_name, daily_file_sha256 FROM batch.cycle "
                + "WHERE cycle_id = :c").param("c", cycle).query().singleRow();
        assertThat(row.get("daily_file_name")).isEqualTo(copy.getFileName().toString());
        assertThat((byte[]) row.get("daily_file_sha256")).isEqualTo(DailyFiles.sha256(content));
    }

    private static void assertStaged(long cycle, List<String> lines) {
        assertThat(DB.admin().sql("SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c ORDER BY seq")
                .param("c", cycle).query(String.class).list()).containsExactlyElementsOf(lines);
    }

    private static Map<String, Object> leaseRow(long id) {
        return DB.admin().sql("SELECT * FROM batch.execution_lease WHERE job_execution_id = :id")
                .param("id", id).query().singleRow();
    }

    private static void awaitEnd(JobExecution execution) throws InterruptedException {
        await("execution " + execution.getId() + " ends", Duration.ofSeconds(60), () -> execution.getEndTime() != null);
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

    /** A published file as a replacement would change it: its bytes, its inode and its modification time. */
    record FileState(String sha256, Object inode, FileTime modified) {

        static FileState of(Path file) throws IOException {
            return new FileState(HexFormat.of().formatHex(DailyFiles.sha256(Files.readAllBytes(file))),
                    Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
        }
    }
}
