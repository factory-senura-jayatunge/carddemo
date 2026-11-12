package org.carddemo.batch.transactionreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A failure after the transaction that completed the run row and before the job repository
 * recorded the job's end: the restart of the same instance verifies the published report and
 * completes, the row keeps the earlier execution's COMPLETED, and no handler sets it back.
 * Interval 0.5 s, TTL 2 s and grace 2 s, as in ExecutionLeaseTest.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.batch.async-launch=true",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionReportRestartTest {
    static final TestDatabase DB = TransactionReportJobTest.DB;

    @Autowired JobLaunches launches;
    @Autowired JobRepository repository;
    @Autowired OutputRoots roots;
    @Autowired TransactionReportTasklet report;
    @Autowired LeaseKeeper keeper;
    @Autowired ExecutionLeases leases;

    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final AtomicLong pausedExecution = new AtomicLong();
    /** Every execution a test launched, so that none still runs when the next test empties the tables. */
    final List<JobExecution> started = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void data() {
        TransactionReportJobTest.fixtures();
        TransactionReportJobTest.transaction("0000000000000001", TransactionReportJobTest.CARD_1, "97", "2022-07-01",
                "10.00", 1);
        TransactionReportJobTest.transaction("0000000000000002", TransactionReportJobTest.CARD_2, "97", "2022-07-02",
                "20.00", 1);
    }

    /** Releases a paused owner and waits, bounded, until every launched execution has ended. */
    @AfterEach
    void resumeEveryOwnerAndAwaitItsEnd() throws InterruptedException {
        resume.countDown();
        keeper.suspendRenewals(false);
        report.afterCompletedCommit = job -> { };
        for (JobExecution execution : started) {
            await("execution " + execution.getId() + " ends after the test", Duration.ofSeconds(60),
                    () -> execution.getEndTime() != null && !keeper.owns(execution.getId()));
        }
        started.clear();
    }

    /** The case the ruling names: a crash after the COMPLETED commit, recovery, then the restart. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRestartAfterAnOwnerLostPastItsCompletedCommitVerifiesAndCompletes() throws Exception {
        report.afterCompletedCommit = job -> {
            if (pausedExecution.compareAndSet(0, job.getId())) {
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
        long runId = TransactionReportJobTest.requested();
        JobExecution a = start(runId);
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A reaches the point after its COMPLETED commit").isTrue();
        assertThat(pausedExecution.get()).isEqualTo(a.getId());
        Map<String, Object> completed = TransactionReportJobTest.runRow(runId);
        assertThat(completed).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", a.getId());
        Path file = report(runId);
        FileState published = FileState.of(file);
        assertThat(completed).containsEntry("output_sha256", TransactionReportJobTest.sha256(file));
        keeper.suspendRenewals(true);
        await("A's lease is recoverable", Duration.ofSeconds(10), () -> leases.recoverable(a.getId()));

        JobExecution b = start(runId);
        keeper.suspendRenewals(false);
        awaitEnd(b);

        JobExecution restarted = repository.getJobExecution(b.getId());
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getId()).isNotEqualTo(a.getId());
        assertThat(restarted.getJobInstance().getId()).isEqualTo(a.getJobInstance().getId());
        assertThat(restarted.getExecutionContext().getString(ReportPublication.OUTPUT))
                .isEqualTo(ReportPublication.relativePath(runId));
        assertLost(a);
        assertThat(TransactionReportJobTest.runRow(runId)).as("the row A completed is unchanged").isEqualTo(completed);
        assertThat(FileState.of(file)).as("the report is verified, never replaced").isEqualTo(published);
        assertThat(names(file.getParent())).containsExactly(ReportPublication.TARGET);

        resume.countDown();
        awaitEnd(a);

        assertLost(a);
        assertThat(TransactionReportJobTest.runRow(runId)).isEqualTo(completed);
        assertThat(FileState.of(file)).isEqualTo(published);
    }

    /** The same point without a crash: the job fails there, and its listener leaves COMPLETED alone. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFailureAfterTheCompletedCommitKeepsTheRowAndTheRestartCompletes() throws Exception {
        report.afterCompletedCommit = job -> {
            if (pausedExecution.compareAndSet(0, job.getId())) {
                throw new IllegalStateException("failure after the completed commit");
            }
        };
        long runId = TransactionReportJobTest.requested();
        JobExecution a = start(runId);
        awaitEnd(a);
        assertThat(repository.getJobExecution(a.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
        Map<String, Object> completed = TransactionReportJobTest.runRow(runId);
        assertThat(completed).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", a.getId())
                .containsEntry("failure_code", null);
        Path file = report(runId);
        FileState published = FileState.of(file);
        assertThat(completed).containsEntry("output_sha256", TransactionReportJobTest.sha256(file));

        JobExecution b = start(runId);
        awaitEnd(b);

        assertThat(repository.getJobExecution(b.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(b.getJobInstance().getId()).isEqualTo(a.getJobInstance().getId());
        assertThat(TransactionReportJobTest.runRow(runId)).isEqualTo(completed);
        assertThat(FileState.of(file)).isEqualTo(published);
        assertThat(names(file.getParent())).containsExactly(ReportPublication.TARGET);
    }

    /** A published report that no longer has its intent's bytes is never replaced and never downgrades the row. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRestartThatFindsOtherBytesFailsAndKeepsTheCompletedRow() throws Exception {
        report.afterCompletedCommit = job -> {
            if (pausedExecution.compareAndSet(0, job.getId())) {
                throw new IllegalStateException("failure after the completed commit");
            }
        };
        long runId = TransactionReportJobTest.requested();
        JobExecution a = start(runId);
        awaitEnd(a);
        Map<String, Object> completed = TransactionReportJobTest.runRow(runId);
        assertThat(completed).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", a.getId());
        Path file = report(runId);
        assertThat(completed).containsEntry("output_sha256", TransactionReportJobTest.sha256(file));
        byte[] other = "changed after publication\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(file, other);

        JobExecution b = start(runId);
        awaitEnd(b);

        JobExecution failed = repository.getJobExecution(b.getId());
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).startsWith("PUBLICATION_MISMATCH");
        assertThat(TransactionReportJobTest.runRow(runId)).isEqualTo(completed);
        assertThat(Files.readAllBytes(file)).isEqualTo(other);
        assertThat(names(file.getParent())).containsExactly(ReportPublication.TARGET);
    }

    /** A completed row whose stored hash is not its intent's fails the restart, which changes nothing. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRestartWhoseCompletedRowHoldsAnotherHashFailsAndChangesNothing() throws Exception {
        report.afterCompletedCommit = job -> {
            if (pausedExecution.compareAndSet(0, job.getId())) {
                throw new IllegalStateException("failure after the completed commit");
            }
        };
        long runId = TransactionReportJobTest.requested();
        JobExecution a = start(runId);
        awaitEnd(a);
        Path file = report(runId);
        FileState published = FileState.of(file);
        DB.admin().sql("UPDATE batch.report_run SET output_sha256 = :other WHERE report_run_id = :id")
                .param("other", new byte[32]).param("id", runId).update();
        Map<String, Object> tampered = TransactionReportJobTest.runRow(runId);
        assertThat(tampered).containsEntry("status", "COMPLETED").containsEntry("output_sha256", "0".repeat(64));

        JobExecution b = start(runId);
        awaitEnd(b);

        JobExecution failed = repository.getJobExecution(b.getId());
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).startsWith("PUBLICATION_MISMATCH");
        assertThat(TransactionReportJobTest.runRow(runId)).isEqualTo(tampered);
        assertThat(FileState.of(file)).isEqualTo(published);
        assertThat(names(file.getParent())).containsExactly(ReportPublication.TARGET);
    }

    private JobExecution start(long runId) {
        JobExecution execution = launches.launch(TransactionReportConfiguration.JOB,
                Map.of("params", Map.of("runId", runId))).execution();
        started.add(execution);
        return execution;
    }

    /** Derived from the database marker, independently of the execution's bound root. */
    private Path report(long runId) throws Exception {
        try (var connection = DB.adminDataSource().getConnection()) {
            return OutputRoot.root(roots.varDir().toRealPath(), OutputRoot.read(connection))
                    .resolve(ReportPublication.relativePath(runId));
        }
    }

    private static void assertLost(JobExecution a) {
        Map<String, Object> row = DB.admin().sql("SELECT STATUS, EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION "
                + "WHERE JOB_EXECUTION_ID = :id").param("id", a.getId()).query().singleRow();
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat((String) row.get("EXIT_MESSAGE")).startsWith("OWNER_LOST");
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
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
            return new FileState(OutputPublication.sha256(file),
                    Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
        }
    }
}
