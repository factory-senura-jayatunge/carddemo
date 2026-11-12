package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.JobKeyGenerator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;

/**
 * Execution ownership against SQL Server (batch-execution-contract.md, section 4.7), with
 * interval 0.5 s, TTL 2 s and grace 2 s. A probe job inserts one row of batch.lease_probe
 * per transaction and waits at a per-execution gate before each, so a test decides when an
 * owner commits; suspending the renewals and holding the gate stands in for a paused owner.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "carddemo.batch.async-launch=true",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
class ExecutionLeaseTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final Map<Long, Semaphore> GATES = new ConcurrentHashMap<>();
    static final Duration RECOVERABLE = Duration.ofSeconds(4);

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    Job leaseProbeJob;

    @Autowired
    LeaseKeeper keeper;

    @Autowired
    ExecutionLeases leases;

    @Autowired
    ExecutionRecovery recovery;

    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeJob {

        @Bean
        Job leaseProbeJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db) {
            Step probe = new StepBuilder("probe", jobRepository).tasklet((contribution, chunk) -> {
                StepExecution step = chunk.getStepContext().getStepExecution();
                long units = step.getJobParameters().getLong("units");
                int done = step.getExecutionContext().getInt("done", 0);
                Semaphore gate = GATES.computeIfAbsent(step.getJobExecutionId(), id -> new Semaphore(0));
                if (!gate.tryAcquire(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate not opened");
                }
                db.sql("INSERT INTO batch.lease_probe (n, job_execution_id) VALUES (:n, :e)")
                        .param("n", done + 1).param("e", step.getJobExecutionId()).update();
                step.getExecutionContext().putInt("done", done + 1);
                return done + 1 < units ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
            }, tx).build();
            return new JobBuilder("leaseProbeJob", jobRepository).start(probe).build();
        }
    }

    @BeforeAll
    static void probeTable() {
        DB.admin().sql("""
                IF OBJECT_ID('batch.lease_probe') IS NULL
                    CREATE TABLE batch.lease_probe (n INT NOT NULL PRIMARY KEY, job_execution_id BIGINT NOT NULL)""")
                .update();
    }

    @BeforeEach
    void empty() {
        DB.admin().sql("DELETE FROM batch.lease_probe").update();
    }

    @AfterEach
    void resume() {
        keeper.suspendRenewals(false);
        GATES.values().forEach(g -> g.release(100));
    }

    private JobExecution start(long units) throws Exception {
        JobParameters params = new JobParametersBuilder().addString("run", UUID.randomUUID().toString())
                .addLong("units", units).toJobParameters();
        return jobOperator.start(leaseProbeJob, params);
    }

    private static void open(JobExecution execution, int units) {
        GATES.computeIfAbsent(execution.getId(), id -> new Semaphore(0)).release(units);
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

    private static int probeRows() {
        return DB.admin().sql("SELECT COUNT(*) FROM batch.lease_probe").query(Integer.class).single();
    }

    private static Map<String, Object> executionRow(long id) {
        return DB.admin().sql("SELECT STATUS, EXIT_MESSAGE, VERSION FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", id).query().singleRow();
    }

    private static Map<String, Object> leaseRow(long id) {
        return DB.admin().sql("SELECT * FROM batch.execution_lease WHERE job_execution_id = :id")
                .param("id", id).query().singleRow();
    }

    private static void awaitEnd(JobExecution execution) throws InterruptedException {
        await("execution " + execution.getId() + " ends", Duration.ofSeconds(20), () -> execution.getEndTime() != null);
        Thread.sleep(200);
    }

    private JobExecution awaitCompleted(JobExecution execution) throws InterruptedException {
        await("execution " + execution.getId() + " completes", Duration.ofSeconds(20),
                () -> !jobRepository.getJobExecution(execution.getId()).isRunning());
        JobExecution stored = jobRepository.getJobExecution(execution.getId());
        assertThat(stored.getStatus()).as(stored.getAllFailureExceptions().toString()).isEqualTo(BatchStatus.COMPLETED);
        return stored;
    }

    @Test
    void theLeaseIsTakenWithTheExecutionAndRenewedOnTheDatabaseClock() throws Exception {
        JobExecution execution = start(2);
        long id = execution.getId();

        Map<String, Object> lease = leaseRow(id);
        assertThat(lease.get("owner_id").toString()).isEqualToIgnoringCase(leases.owner().id().toString());
        assertThat(lease.get("owner_pid")).isEqualTo((int) ProcessHandle.current().pid());
        assertThat(lease.get("revoked_at")).isNull();
        assertThat(lease.get("recoverable_after_ms")).as("TTL + grace").isEqualTo((int) RECOVERABLE.toMillis());
        assertThat(keeper.owns(id)).isTrue();

        Thread.sleep(RECOVERABLE.plusSeconds(1).toMillis());
        LeaseView live = leases.view(id).orElseThrow();
        assertThat(live.state()).isEqualTo(LeaseView.State.LIVE);
        assertThat(live.recoverable()).isFalse();
        assertThat(live.renewedAt()).isAfter(((java.sql.Timestamp) lease.get("acquired_at")).toLocalDateTime());
        assertThat(recovery.recover(id)).as("a live owner is never recovered")
                .isInstanceOf(ExecutionRecovery.Outcome.NotRecoverable.class);

        open(execution, 2);
        awaitCompleted(execution);
        assertThat(probeRows()).isEqualTo(2);
        assertThat(keeper.owns(id)).as("no renewals after the end").isFalse();
        assertThat(leaseRow(id).get("revoked_at")).isNull();
    }

    @Test
    void everyStepOfEveryJobRunsThroughTheFencingTransactionManager() {
        assertThat(context.getBeansOfType(PlatformTransactionManager.class).values())
                .as("the plain transaction manager is no bean")
                .singleElement().isInstanceOf(FencingTransactionManager.class);
        Map<String, Job> jobs = context.getBeansOfType(Job.class);
        assertThat(jobs).containsKeys("dataLoadJob", "leaseProbeJob");
        for (Job job : jobs.values()) {
            assertThat(job).as(job.getName()).isInstanceOf(StepLocator.class);
            StepLocator locator = (StepLocator) job;
            Method stepNames = ReflectionUtils.findMethod(job.getClass(), "getStepNames");
            assertThat(stepNames).as(job.getName() + " (" + job.getClass().getSimpleName() + ") lists its steps")
                    .isNotNull();
            @SuppressWarnings("unchecked")
            Collection<String> names = (Collection<String>) ReflectionUtils.invokeMethod(stepNames, job);
            assertThat(names).as(job.getName()).isNotEmpty();
            for (String name : names) {
                Step step = locator.getStep(name);
                Field field = ReflectionUtils.findField(step.getClass(), "transactionManager");
                assertThat(field).as(job.getName() + "." + name + " (" + step.getClass().getSimpleName()
                        + "): add the check for this step type").isNotNull();
                ReflectionUtils.makeAccessible(field);
                assertThat(ReflectionUtils.getField(field, step)).as(job.getName() + "." + name)
                        .isInstanceOf(FencingTransactionManager.class);
            }
        }
    }

    @Test
    void aTransactionWithNoBoundStepExecutionIsRefused() {
        PlatformTransactionManager fencing = context.getBean(PlatformTransactionManager.class);
        assertThatThrownBy(() -> new TransactionTemplate(fencing).executeWithoutResult(s -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("step or job execution");
    }

    @Test
    void anOwnerPausedPastTtlPlusGraceIsRecoveredByALaunchAndCommitsNothingAfterwards() throws Exception {
        JobExecution execution = start(3);
        long id = execution.getId();
        open(execution, 1);
        await("the first unit commits", Duration.ofSeconds(10), () -> probeRows() == 1);
        keeper.suspendRenewals(true);
        int version = ((Number) executionRow(id).get("VERSION")).intValue();
        long report = DB.admin().sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, status, job_execution_id,
                    started_at)
                OUTPUT inserted.report_run_id
                VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'LEASET01', 'RUNNING', :e, SYSUTCDATETIME())""")
                .param("e", id).query(Long.class).single();
        DB.admin().sql("INSERT INTO batch.export_run (job_instance_id, job_execution_id, business_date) "
                + "VALUES (:i, :e, '2022-07-06')").param("i", execution.getJobInstanceId()).param("e", id).update();
        String stalled = "SELECT stalled FROM batch.v_report_run_stalled WHERE report_run_id = :r";

        // The poll's view goes LIVE, EXPIRED, then recoverable, and never changes the execution.
        assertThat(leases.view(id).orElseThrow().state()).isEqualTo(LeaseView.State.LIVE);
        await("the lease expires", Duration.ofSeconds(5),
                () -> leases.view(id).orElseThrow().state() == LeaseView.State.EXPIRED);
        assertThat(leases.view(id).orElseThrow().recoverable()).isFalse();
        assertThat(DB.admin().sql(stalled).param("r", report).query(Boolean.class).single()).isFalse();
        await("the lease is recoverable", Duration.ofSeconds(5), () -> leases.view(id).orElseThrow().recoverable());
        assertThat(executionRow(id)).containsEntry("STATUS", "STARTED").containsEntry("VERSION", (long) version);
        assertThat(DB.admin().sql(stalled).param("r", report).query(Boolean.class).single())
                .as("the database view agrees with the lease view").isTrue();

        ExecutionRecovery.Outcome outcome = recovery.recover(id);
        assertThat(outcome).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
        assertThat(((ExecutionRecovery.Outcome.Recovered) outcome).exitMessage())
                .startsWith("OWNER_LOST: lease not renewed since ").endsWith(leases.owner().toString());
        assertThat(leaseRow(id)).containsEntry("revoked_reason", "OWNER_LOST");
        for (String table : new String[] {"batch.report_run", "batch.export_run"}) {
            assertThat(DB.admin().sql("SELECT status, failure_code FROM %s WHERE job_execution_id = :e".formatted(table))
                    .param("e", id).query().singleRow()).as(table)
                    .containsEntry("status", "FAILED").containsEntry("failure_code", "OWNER_LOST");
        }
        assertThat(DB.admin().sql(stalled).param("r", report).query(Boolean.class).single()).isFalse();
        assertThat(recovery.recover(id)).as("a second launch finds it recovered")
                .isInstanceOf(ExecutionRecovery.Outcome.NotRecoverable.class);

        // The paused owner resumes: its next commit fails and it overwrites nothing. Its first write
        // is a step update with a stale version; that conflict once let Spring Batch write UNKNOWN.
        keeper.suspendRenewals(false);
        open(execution, 1);
        awaitEnd(execution);
        assertThat(keeper.isLost(id)).isTrue();
        assertThat(probeRows()).isEqualTo(1);
        Map<String, Object> failed = executionRow(id);
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat((String) failed.get("EXIT_MESSAGE")).startsWith("OWNER_LOST");
        assertThat(DB.admin().sql("SELECT STATUS FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", id).query(String.class).list()).containsOnly("FAILED");

        // The restart resumes after the last committed unit; the result equals an uninterrupted run.
        JobExecution restart = jobOperator.restart(jobRepository.getJobExecution(id));
        open(restart, 2);
        awaitCompleted(restart);
        assertThat(DB.admin().sql("SELECT n FROM batch.lease_probe ORDER BY n").query(Integer.class).list())
                .containsExactly(1, 2, 3);
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");
    }

    @Test
    void aPauseShorterThanTtlPlusGraceRecoversNothing() throws Exception {
        JobExecution execution = start(2);
        long id = execution.getId();
        open(execution, 1);
        await("the first unit commits", Duration.ofSeconds(10), () -> probeRows() == 1);
        keeper.suspendRenewals(true);
        await("the lease expires", Duration.ofSeconds(5),
                () -> leases.view(id).orElseThrow().state() == LeaseView.State.EXPIRED);
        assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.NotRecoverable.class);
        Thread.sleep(500);
        keeper.suspendRenewals(false);

        open(execution, 1);
        awaitCompleted(execution);
        assertThat(probeRows()).isEqualTo(2);
        assertThat(keeper.isLost(id)).isFalse();
        assertThat(leaseRow(id).get("revoked_at")).isNull();
    }

    @Test
    void aRevokedLeaseRollsBackTheOwnersTransaction() throws Exception {
        JobExecution execution = start(2);
        long id = execution.getId();
        open(execution, 1);
        await("the first unit commits", Duration.ofSeconds(10), () -> probeRows() == 1);
        keeper.suspendRenewals(true);
        DB.admin().sql("UPDATE batch.execution_lease SET revoked_at = SYSUTCDATETIME(), revoked_reason = 'TEST' "
                + "WHERE job_execution_id = :id").param("id", id).update();

        open(execution, 1);
        awaitEnd(execution);
        assertThat(probeRows()).as("the fence rolled the second unit back").isEqualTo(1);
        assertThat(keeper.isLost(id)).isTrue();
        assertThat(execution.getAllFailureExceptions().toString()).contains(LeaseLostException.CODE);
        assertThat(executionRow(id).get("STATUS")).as("no status write after the loss").isEqualTo("STARTED");
    }

    @Test
    void anExecutionWithoutALeaseIsRecoverableOnceLastUpdatedIsTheFixed120SecondsOld() throws Exception {
        assertThat(java.util.TimeZone.getDefault().getID()).as("the JVM zone, as in production").isEqualTo("UTC");
        JobExecution execution = start(2);
        long id = execution.getId();
        // Spring Batch's start writes set LAST_UPDATED; the step then waits at the gate and writes nothing.
        await("the step waits at the gate", Duration.ofSeconds(10), () -> DB.admin()
                .sql("SELECT COUNT(*) FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id AND STATUS = 'STARTED'")
                .param("id", id).query(Integer.class).single() == 1);
        keeper.suspendRenewals(true);
        DB.admin().sql("DELETE FROM batch.execution_lease WHERE job_execution_id = :id").param("id", id).update();

        // Far past this process's TTL + grace (4 s): the reader's settings are not used.
        DB.admin().sql("UPDATE batch.BATCH_JOB_EXECUTION SET LAST_UPDATED = DATEADD(SECOND, -110, SYSUTCDATETIME()) "
                + "WHERE JOB_EXECUTION_ID = :id").param("id", id).update();
        assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.NotRecoverable.class);
        assertThat(leases.view(id)).isEmpty();

        DB.admin().sql("UPDATE batch.BATCH_JOB_EXECUTION SET LAST_UPDATED = DATEADD(SECOND, -125, SYSUTCDATETIME()) "
                + "WHERE JOB_EXECUTION_ID = :id").param("id", id).update();
        assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
        assertThat(leases.view(id).orElseThrow().state()).isEqualTo(LeaseView.State.REVOKED);
        assertThat(leaseRow(id)).containsEntry("recoverable_after_ms", 120000).containsEntry("revoked_reason", "OWNER_LOST");
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");

        keeper.suspendRenewals(false);
        open(execution, 2);
        awaitEnd(execution);
        assertThat(probeRows()).isZero();
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");
    }

    /** A job instance and execution as another batch-service process would have created them. */
    private static void foreignExecution(long id) {
        dropForeignExecution(id);
        DB.admin().sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                + "VALUES (:id, 0, 'leaseProbeJob', CONCAT('foreign', :id))").param("id", id).update();
        DB.admin().sql("INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, "
                + "START_TIME, LAST_UPDATED, STATUS) VALUES (:id, 1, :id, GETUTCDATE(), GETUTCDATE(), GETUTCDATE(), 'STARTED')")
                .param("id", id).update();
    }

    private static void dropForeignExecution(long id) {
        DB.admin().sql("DELETE FROM batch.execution_lease WHERE job_execution_id = :id").param("id", id).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id").param("id", id).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id").param("id", id).update();
    }

    private static void foreignLease(long id, int recoverableAfterMs, int renewedMsAgo) {
        DB.admin().sql("""
                INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid, renewed_at,
                    recoverable_after_ms)
                VALUES (:id, NEWID(), 'other-host', 1, DATEADD(MILLISECOND, -:ago, SYSUTCDATETIME()), :limit)""")
                .param("id", id).param("ago", renewedMsAgo).param("limit", recoverableAfterMs).update();
    }

    @Test
    void eachLeaseIsRecoverableAtItsOwnersLimitWhateverTheReadersSettings() {
        long longer = -11;
        long shorter = -12;
        try {
            foreignExecution(longer);
            foreignExecution(shorter);
            // This process would call both recoverable after 4 s; the owners stored 10 s and 1 s.
            foreignLease(longer, 10_000, 5_000);
            foreignLease(shorter, 1_000, 2_000);

            assertThat(leases.view(longer).orElseThrow().recoverable()).isFalse();
            assertThat(recovery.recover(longer)).isInstanceOf(ExecutionRecovery.Outcome.NotRecoverable.class);
            assertThat(leaseRow(longer).get("revoked_at")).isNull();

            assertThat(leases.view(shorter).orElseThrow().recoverable()).isTrue();
            assertThat(recovery.recover(shorter)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
            assertThat(executionRow(shorter).get("STATUS")).isEqualTo("FAILED");

            DB.admin().sql("UPDATE batch.execution_lease SET renewed_at = DATEADD(MILLISECOND, -11000, SYSUTCDATETIME()) "
                    + "WHERE job_execution_id = :id").param("id", longer).update();
            assertThat(leases.view(longer).orElseThrow().recoverable()).isTrue();
            assertThat(recovery.recover(longer)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
            assertThat(leaseRow(longer)).containsEntry("recoverable_after_ms", 10_000);
        } finally {
            dropForeignExecution(longer);
            dropForeignExecution(shorter);
        }
    }

    /** A statement run of {@code execution} on a new cycle, as the capture or a claim leaves it. */
    private static long statementRun(long execution, String businessDate, String status) {
        long cycle = DB.admin().sql("INSERT INTO batch.cycle (business_date) OUTPUT inserted.cycle_id VALUES (:d)")
                .param("d", businessDate).query(Long.class).single();
        boolean publishing = status.equals("PUBLISHING");
        return DB.admin().sql("""
                INSERT INTO batch.statement_run (cycle_id, attempt, mode, status, watermark_tran_pk, folder, work_folder,
                    manifest_sha256, statements, transactions, unassigned, adjusted, job_execution_id)
                OUTPUT inserted.statement_run_id
                VALUES (:c, 1, 'LEGACY', :s, 0, CONCAT(:c, '-1'), :work, :sha, :n, :n, :n, :n, :e)""")
                .param("c", cycle).param("s", status)
                .param("work", publishing ? cycle + "-1." + (-execution) : null, java.sql.Types.VARCHAR)
                .param("sha", publishing ? "0".repeat(64) : null, java.sql.Types.CHAR)
                .param("n", publishing ? 0 : null, java.sql.Types.INTEGER)
                .param("e", execution).query(Long.class).single();
    }

    private static Map<String, Object> statementRunRow(long run) {
        return DB.admin().sql("SELECT status, failure_code, job_execution_id FROM batch.statement_run "
                + "WHERE statement_run_id = :r").param("r", run).query().singleRow();
    }

    private static void dropStatementRuns(String... businessDates) {
        for (String date : businessDates) {
            DB.admin().sql("""
                    DELETE r FROM batch.statement_run AS r JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id
                    WHERE c.business_date = :d""").param("d", date).update();
            DB.admin().sql("DELETE FROM batch.cycle WHERE business_date = :d").param("d", date).update();
        }
    }

    /**
     * Recovery fails only the statement run whose job_execution_id is the recovered execution, and
     * only while it is RUNNING (batch.md, "Runs, restart and rerun", owner binding).
     */
    @Test
    void recoveryFailsOnlyTheRunningStatementRunOfTheRecoveredExecution() {
        long recovered = -21;
        long other = -22;
        long publishing = -23;
        String[] dates = {"2101-07-01", "2101-07-02", "2101-07-03"};
        try {
            dropStatementRuns(dates);
            foreignExecution(recovered);
            foreignExecution(other);
            foreignExecution(publishing);
            foreignLease(recovered, 1_000, 2_000);
            foreignLease(publishing, 1_000, 2_000);
            long ownRun = statementRun(recovered, dates[0], "RUNNING");
            long otherRun = statementRun(other, dates[1], "RUNNING");
            long publishingRun = statementRun(publishing, dates[2], "PUBLISHING");

            assertThat(recovery.recover(recovered)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
            assertThat(statementRunRow(ownRun)).containsEntry("status", "FAILED")
                    .containsEntry("failure_code", "OWNER_LOST").containsEntry("job_execution_id", recovered);
            assertThat(statementRunRow(otherRun)).as("another cycle's RUNNING run")
                    .containsEntry("status", "RUNNING").containsEntry("failure_code", null);

            assertThat(recovery.recover(publishing)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
            assertThat(statementRunRow(publishingRun)).as("a committed intent is kept for the restart's claim")
                    .containsEntry("status", "PUBLISHING").containsEntry("failure_code", null);
        } finally {
            dropStatementRuns(dates);
            dropForeignExecution(recovered);
            dropForeignExecution(other);
            dropForeignExecution(publishing);
        }
    }
    /** Before each recovery statement set its own lock timeout, this recovery waited forever. */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aLeaseRowHeldByTheOwnerBetweenFenceAndCommitAnswersRunning() throws Exception {
        JobExecution execution = start(1);
        long id = execution.getId();
        keeper.suspendRenewals(true);
        await("the lease is recoverable", Duration.ofSeconds(8), () -> leases.view(id).orElseThrow().recoverable());

        try (Connection owner = DB.adminDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try (PreparedStatement fence = owner.prepareStatement(
                    "UPDATE batch.execution_lease SET owner_host = owner_host WHERE job_execution_id = ?")) {
                fence.setLong(1, id);
                fence.executeUpdate();
            }
            long started = System.nanoTime();
            assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.Locked.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(15));
            owner.rollback();
        }
        assertThat(executionRow(id).get("STATUS")).isEqualTo("STARTED");
        assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void concurrentRecoveriesOfOneExecutionRecoverItOnceInBoundedTime() throws Exception {
        JobExecution execution = start(1);
        long id = execution.getId();
        keeper.suspendRenewals(true);
        await("the lease is recoverable", Duration.ofSeconds(8), () -> leases.view(id).orElseThrow().recoverable());

        ExecutorService launches = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ExecutionRecovery.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(launches.submit(() -> {
                    go.await();
                    return recovery.recover(id);
                }));
            }
            long started = System.nanoTime();
            go.countDown();
            List<ExecutionRecovery.Outcome> outcomes = new ArrayList<>();
            for (Future<ExecutionRecovery.Outcome> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("every launch answers").isLessThan(Duration.ofSeconds(15));
            assertThat(outcomes).filteredOn(o -> o instanceof ExecutionRecovery.Outcome.Recovered).hasSize(1);
            assertThat(outcomes).filteredOn(o -> !(o instanceof ExecutionRecovery.Outcome.Recovered))
                    .allMatch(o -> o instanceof ExecutionRecovery.Outcome.NotRecoverable
                            || o instanceof ExecutionRecovery.Outcome.Locked);
        } finally {
            launches.shutdownNow();
        }
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");
        assertThat(leaseRow(id)).containsEntry("revoked_reason", "OWNER_LOST");
    }

    /**
     * The guarded repository over the application's own delegate, except that the write of an
     * execution's end runs {@code afterEndWrite} once the delegate has written it, inside the
     * delegate's call.
     */
    private LeaseGuardedJobRepository endHooked(Runnable afterEndWrite) {
        JobRepository delegate = (JobRepository) field(jobRepository, "delegate");
        JobRepository hooked = (JobRepository) Proxy.newProxyInstance(JobRepository.class.getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("update") && args[0] instanceof JobExecution e
                            && !e.getStatus().isRunning()) {
                        afterEndWrite.run();
                    }
                    return result;
                });
        return new LeaseGuardedJobRepository(hooked, (SafeTransactions) field(jobRepository, "createTransaction"),
                leases, keeper, (JdbcClient) field(jobRepository, "db"), (JobKeyGenerator) field(jobRepository, "keys"),
                (OutputRoots) field(jobRepository, "roots"));
    }

    private static Object field(Object target, String name) {
        Field f = ReflectionUtils.findField(target.getClass(), name);
        assertThat(f).as(target.getClass().getSimpleName() + "." + name).isNotNull();
        ReflectionUtils.makeAccessible(f);
        return ReflectionUtils.getField(f, target);
    }

    /**
     * The instance lock is the first statement of its transaction, which the caller may own: the
     * caller's commit must end that transaction, release the lock and make the instance durable.
     */
    @Test
    void theInstanceLockTakenFirstIsReleasedAndTheInstanceCommittedAtCommit() {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcClient service = JdbcClient.create(dataSource);
        JobParameters params = new JobParametersBuilder().addString("run", UUID.randomUUID().toString())
                .toJobParameters();
        String key = ((JobKeyGenerator) field(jobRepository, "keys")).generateKey(params);
        Map<String, Object> seen = new HashMap<>();
        new TransactionTemplate(new JdbcTransactionManager(dataSource)).executeWithoutResult(status -> {
            jobRepository.createJobInstance("instanceLockProbe", params);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    seen.put("trancount", service.sql("SELECT @@TRANCOUNT").query(Integer.class).single());
                    seen.put("lockFree", TransactionLocksTest.lockFree("carddemo.batch_instance:instanceLockProbe:" + key));
                    seen.put("instances", DB.admin().sql("""
                                    SET NOCOUNT ON;
                                    SET LOCK_TIMEOUT 2000;
                                    DECLARE @n INT = (SELECT COUNT(*) FROM batch.BATCH_JOB_INSTANCE
                                        WHERE JOB_NAME = 'instanceLockProbe' AND JOB_KEY = :key);
                                    SET LOCK_TIMEOUT -1;
                                    SELECT @n;""")
                            .param("key", key).query(Integer.class).single());
                }
            });
        });
        assertThat(seen).containsEntry("trancount", 0).containsEntry("lockFree", true).containsEntry("instances", 1);
    }

    /** An execution created and started through {@code repository}, with its lease owned here. */
    private JobExecution startedExecution(JobRepository repository) {
        JobParameters params = new JobParametersBuilder().addString("run", UUID.randomUUID().toString())
                .toJobParameters();
        JobInstance instance = repository.createJobInstance("leaseEndProbe", params);
        JobExecution execution = repository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStartTime(LocalDateTime.now());
        execution.setStatus(BatchStatus.STARTED);
        repository.update(execution);
        assertThat(keeper.owns(execution.getId())).isTrue();
        assertThat(executionRow(execution.getId()).get("STATUS")).isEqualTo("STARTED");
        return execution;
    }

    private static void end(JobExecution execution) {
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);
        execution.setEndTime(LocalDateTime.now());
    }

    /**
     * A reader that sees the end must find the lease no longer renewed. The end used to be
     * committed before the renewals stopped, so a reader in between saw both.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theEndOfAnExecutionIsNotVisibleWhileItsLeaseIsStillRenewed() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        LeaseGuardedJobRepository repository = endHooked(() -> {
            written.countDown();
            try {
                resume.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        JobExecution execution = startedExecution(repository);
        long id = execution.getId();
        end(execution);

        ExecutorService ender = Executors.newSingleThreadExecutor();
        try {
            Future<?> ending = ender.submit(() -> repository.update(execution));
            assertThat(written.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(executionRow(id).get("STATUS")).as("the end is written but not committed").isEqualTo("STARTED");
            assertThat(keeper.owns(id)).as("a running execution keeps its renewals").isTrue();
            resume.countDown();
            ending.get(20, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            ender.shutdownNow();
        }
        assertThat(executionRow(id).get("STATUS")).isEqualTo("COMPLETED");
        assertThat(keeper.owns(id)).isFalse();
        assertThat(keeper.isLost(id)).isFalse();
        assertThat(leaseRow(id).get("revoked_at")).isNull();
    }

    /**
     * An end that is not committed leaves no thread to end the execution: the lease is given up,
     * so a launch recovers it, and this process writes nothing more for it. It used to be renewed
     * for as long as the process lived, so the execution could never be recovered.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anEndThatIsNotCommittedGivesTheLeaseUpForRecovery() throws Exception {
        LeaseGuardedJobRepository repository = endHooked(() -> {
            throw new DataAccessResourceFailureException("the connection broke before the commit");
        });
        JobExecution execution = startedExecution(repository);
        long id = execution.getId();
        end(execution);

        assertThatThrownBy(() -> repository.update(execution)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(executionRow(id).get("STATUS")).as("the end was rolled back").isEqualTo("STARTED");
        assertThat(keeper.owns(id)).isFalse();
        assertThat(keeper.isLost(id)).isTrue();

        await("the lease is recoverable", Duration.ofSeconds(20), () -> leases.recoverable(id));
        assertThat(recovery.recover(id)).isInstanceOf(ExecutionRecovery.Outcome.Recovered.class);
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");
        assertThatThrownBy(() -> repository.update(execution)).isInstanceOf(LeaseLostException.class);
        assertThat(executionRow(id).get("STATUS")).isEqualTo("FAILED");
    }

    /** Spring Batch writes the end again after the job listeners; that write failing loses nothing. */
    @Test
    void aFailedSecondWriteOfACommittedEndLosesNothing() throws Exception {
        AtomicInteger endWrites = new AtomicInteger();
        LeaseGuardedJobRepository repository = endHooked(() -> {
            if (endWrites.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("the connection broke");
            }
        });
        JobExecution execution = startedExecution(repository);
        long id = execution.getId();
        end(execution);
        repository.update(execution);
        assertThat(keeper.owns(id)).isFalse();

        assertThatThrownBy(() -> repository.update(execution)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(keeper.isLost(id)).isFalse();
        assertThat(executionRow(id).get("STATUS")).isEqualTo("COMPLETED");
        assertThat(leaseRow(id).get("revoked_at")).isNull();
    }

    @Test
    void leaseSettingsKeepTheContractRatios() {
        assertThatThrownBy(() -> new LeaseSettings(Duration.ofSeconds(10), Duration.ofSeconds(29), Duration.ofSeconds(60)))
                .hasMessageContaining("3 times");
        assertThatThrownBy(() -> new LeaseSettings(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(59)))
                .hasMessageContaining("grace");
        assertThatThrownBy(() -> new LeaseSettings(Duration.ofSeconds(10), Duration.ofDays(13), Duration.ofDays(13)))
                .hasMessageContaining("recoverable_after_ms");
        assertThat(new LeaseSettings(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(60))
                .recoverableAfter()).isEqualTo(Duration.ofSeconds(120));
    }
}
