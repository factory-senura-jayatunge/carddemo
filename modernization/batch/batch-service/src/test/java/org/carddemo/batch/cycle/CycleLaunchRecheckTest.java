package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.RequestTransactions;
import org.carddemo.batch.internal.BatchWindows;
import org.carddemo.batch.internal.Cycle;
import org.carddemo.batch.internal.CycleReruns;
import org.carddemo.batch.internal.Cycles;
import org.carddemo.batch.internal.InternalApiException;
import org.carddemo.batch.internal.InternalApiProperties;
import org.carddemo.batch.internal.JobCatalog;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The cycle row under the launch lock (contract section 2.1, C1 and K1): a cycle-job launch re-reads
 * the cycle while it holds the shared launch lock, only where it would create an instance or an
 * execution, and refuses a COMPLETED cycle (CYCLE_COMPLETED) or an attempt other than the one it
 * prepared (CYCLE_ATTEMPT_CHANGED), creating nothing. The answers that create nothing come first.
 *
 * <p>A launch "prepared before the restore" uses a JobLaunches whose Cycles runs a hook right after
 * the read that prepares the parameters, before the launch asks for the lock: the hook runs
 * cycle-rerun, or raises the attempt directly where a real rerun would refuse the fixture (a live
 * execution). One test holds the launch lock as the restore does, inside a transaction that raises
 * the attempt, and commits only once the launch waits for that lock.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7",
        "carddemo.batch.internal.launch-lock-timeout=60s"})
class CycleLaunchRecheckTest extends CycleJobTestBase {

    private static final String DATE = "2101-04-14";
    private static final String JOB = "postDailyTransactionsJob";
    private static final String PURGE = "purgePendingAuthorizationsJob";
    private static final String CARD = "9".repeat(12) + "0001";
    /** Execution IDs of this class's fixture executions, below the sequence and other classes' fakes. */
    private static final long FIRST_FAKE = -7301;
    /** Longer than the 60 s launch-lock wait this class sets, plus the launch's own work. */
    private static final long LAUNCH_END_WAIT_SECONDS = 120;

    @Autowired
    CycleReruns reruns;

    @Autowired
    JobCatalog catalog;

    @Autowired
    List<Job> jobList;

    @Autowired
    JobOperator operator;

    @Autowired
    ExecutionLeases leases;

    @Autowired
    ExecutionRecovery recovery;

    @Autowired
    JdbcClient db;

    @Autowired
    RequestTransactions transactions;

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment environment;

    @Autowired
    InternalApiProperties properties;

    @Autowired
    PlatformTransactionManager tx;

    private long nextFake = FIRST_FAKE;

    @AfterEach
    void dropFixtureExecutions() {
        String fakes = "BETWEEN " + (FIRST_FAKE - 99) + " AND " + FIRST_FAKE;
        List<Long> instances = DB.admin().sql("SELECT DISTINCT JOB_INSTANCE_ID FROM batch.BATCH_JOB_EXECUTION"
                + " WHERE JOB_EXECUTION_ID " + fakes).query(Long.class).list();
        DB.admin().sql("DELETE FROM batch.execution_lease WHERE job_execution_id " + fakes).update();
        DB.admin().sql("DELETE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID " + fakes).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID " + fakes).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID " + fakes).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID " + fakes).update();
        for (long instance : instances) {
            DB.admin().sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :i AND NOT EXISTS"
                    + " (SELECT 1 FROM batch.BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :i)").param("i", instance)
                    .update();
        }
    }

    @Test
    void aStartPreparedBeforeARerunsRestoreIsRefusedAndTheSameRequestThenStartsTheNewAttempt() {
        long cycle = completedCycle();

        InternalApiException refused = refused(() -> afterPrepare(() -> reruns.rerun(cycle)).launch(JOB, request(cycle)));

        assertCycleAnswer(refused, "CYCLE_ATTEMPT_CHANGED", 2, "OPEN");
        assertThat(repository.getJobInstance(JOB, identifying(cycle, 1))).as("no instance of attempt 1").isNull();
        assertThat(cycleExecutions(cycle)).isZero();

        JobLaunches.Launched again = launches.launch(JOB, request(cycle));

        assertThat(again.restart()).isFalse();
        assertThat(again.execution().getJobParameters().getLong("attempt")).isEqualTo(2L);
        assertThat(repository.getJobInstance(JOB, identifying(cycle, 1))).as("still no instance of attempt 1").isNull();
    }

    @Test
    void aRestartPreparedBeforeARerunsRestoreIsRefusedAndAddsNoExecution() {
        long cycle = openFixtureCycle();
        long failed = fixtureExecution(cycle, 1, "FAILED", 0);
        windows.close(WindowHolder.cycle(cycle), true);

        InternalApiException refused = refused(() -> afterPrepare(() -> reruns.rerun(cycle)).launch(JOB, request(cycle)));

        assertCycleAnswer(refused, "CYCLE_ATTEMPT_CHANGED", 2, "OPEN");
        assertThat(instanceExecutions(failed)).as("the FAILED instance of attempt 1 gets no new execution").isOne();
        assertThat(executionStatus(failed)).isEqualTo("FAILED");
    }

    /** Before its restore, cycle-rerun's window is open for a COMPLETED cycle; the window check would let the launch in. */
    @Test
    void aCompletedCycleIsRefusedEvenWhereItsWindowIsHeld() {
        long cycle = completedCycle();
        JobLaunches windowHeld = launches(cycles, heldWindows(), jobsByName());

        InternalApiException refused = refused(() -> windowHeld.launch(JOB, request(cycle)));
        InternalApiException plain = refused(() -> launches.launch(JOB, request(cycle)));

        assertCycleAnswer(refused, "CYCLE_COMPLETED", 1, "COMPLETED");
        assertThat(plain.code()).as("the cycle is checked before the window").isEqualTo("CYCLE_COMPLETED");
        assertThat(repository.getJobInstance(JOB, identifying(cycle, 1))).isNull();
        assertThat(cycleExecutions(cycle)).isZero();
    }

    /**
     * The purge reads and writes only pauth: it is not refused for a COMPLETED cycle and takes no
     * launch lock. A no-op job stands in for it, so that no other class's pauth rows are purged.
     */
    @Test
    void thePurgeOfACompletedCycleIsNotRefusedAndTakesNoLaunchLock() throws Exception {
        long cycle = completedCycle();
        Map<String, Job> jobs = jobsByName();
        jobs.put(PURGE, new JobBuilder(PURGE, repository)
                .start(new StepBuilder("noop", repository).tasklet((c, ctx) -> RepeatStatus.FINISHED, tx).build())
                .build());
        JobLaunches withNoopPurge = launches(cycles, windows, jobs);

        JobLaunches.Launched purge;
        try (Connection restore = DB.adminDataSource().getConnection()) {
            holdLaunchLock(restore);
            try {
                purge = withNoopPurge.launch(PURGE, request(cycle));
            } finally {
                releaseLaunchLock(restore);
            }
        }

        assertThat(purge.restart()).isFalse();
        assertThat(purge.execution().getJobParameters().getLong("attempt")).isEqualTo(1L);
    }

    @Test
    void aClearedCompletedTaskStillCompletesAfterItsCyclesClose() {
        long cycle = openFixtureCycle();
        long completed = fixtureExecution(cycle, 1, "COMPLETED", 0);
        windows.close(WindowHolder.cycle(cycle), true);

        InternalApiException answer = refused(() -> launches.launch(JOB, request(cycle)));

        assertThat(answer.code()).isEqualTo("JOB_INSTANCE_COMPLETE");
        assertThat(answer.body()).containsEntry("executionId", completed).containsEntry("exitStatus", "COMPLETED");
        assertThat(instanceExecutions(completed)).isOne();
    }

    @Test
    void aClearedCompletedTaskOfTheReplacedAttemptStillCompletesAfterTheRerun() {
        long cycle = openFixtureCycle();
        long completed = fixtureExecution(cycle, 1, "COMPLETED", 0);
        windows.close(WindowHolder.cycle(cycle), true);

        InternalApiException answer = refused(() -> afterPrepare(() -> reruns.rerun(cycle)).launch(JOB, request(cycle)));

        assertThat(answer.code()).isEqualTo("JOB_INSTANCE_COMPLETE");
        assertThat(answer.body()).containsEntry("executionId", completed);
        assertThat(attempt(cycle)).as("the rerun did restore").isEqualTo(2);
        assertThat(instanceExecutions(completed)).isOne();
    }

    @Test
    void aLiveExecutionOfTheReplacedAttemptIsStillAnsweredAsRunning() {
        long cycle = openFixtureCycle();
        long live = fixtureExecution(cycle, 1, "STARTED", 0);

        InternalApiException answer = refused(() -> afterPrepare(() -> raiseAttempt(cycle)).launch(JOB, request(cycle)));

        assertThat(answer.code()).isEqualTo("JOB_EXECUTION_RUNNING");
        assertThat(answer.body()).containsEntry("executionId", live);
        assertThat(executionStatus(live)).isEqualTo("STARTED");
        assertThat(instanceExecutions(live)).isOne();
    }

    @Test
    void aRecoverableExecutionOfTheReplacedAttemptIsRefusedAndNotRecovered() {
        long cycle = openFixtureCycle();
        long lost = fixtureExecution(cycle, 1, "STARTED", -600);
        assertThat(leases.recoverable(lost)).as("the fixture's owner is lost").isTrue();

        InternalApiException refused = refused(() -> afterPrepare(() -> raiseAttempt(cycle)).launch(JOB, request(cycle)));

        assertCycleAnswer(refused, "CYCLE_ATTEMPT_CHANGED", 2, "OPEN");
        assertThat(executionStatus(lost)).as("not recovered").isEqualTo("STARTED");
        assertThat(instanceExecutions(lost)).as("not restarted").isOne();
    }

    /**
     * The restore's own order: it holds the launch lock exclusively in a transaction that raises the
     * attempt. A launch prepared before the commit waits for the lock, then reads the raised attempt.
     * The cycle's window is held, so without the re-read the launch would start attempt 1.
     */
    @Test
    void aLaunchWaitingOnTheLaunchLockReadsTheAttemptTheRestoreCommitted() throws Exception {
        long cycle = openFixtureCycle();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Throwable failure = null;
        try (Connection restore = DB.adminDataSource().getConnection()) {
            restore.setAutoCommit(false);
            boolean committed = false;
            try {
                int spid;
                try (Statement s = restore.createStatement()) {
                    s.execute("EXEC sp_getapplock @Resource = 'carddemo.batch_launch', @LockMode = 'Exclusive',"
                            + " @LockOwner = 'Transaction'");
                    s.executeUpdate("UPDATE batch.cycle SET attempt = attempt + 1 WHERE cycle_id = " + cycle);
                    try (ResultSet rs = s.executeQuery("SELECT @@SPID")) {
                        rs.next();
                        spid = rs.getInt(1);
                    }
                }
                Future<JobLaunches.Launched> launch = executor.submit(() -> launches.launch(JOB, request(cycle)));
                awaitBlockedBy(spid);
                restore.commit();
                committed = true;

                ExecutionException failed = null;
                try {
                    launch.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failed = e;
                }
                assertThat(failed).as("the launch is refused").isNotNull();
                assertThat(failed.getCause()).isInstanceOf(InternalApiException.class);
                assertCycleAnswer((InternalApiException) failed.getCause(), "CYCLE_ATTEMPT_CHANGED", 2, "OPEN");
            } finally {
                if (!committed) {
                    // Ends the transaction that owns the launch lock, so a launch still waiting on it
                    // gets its answer now rather than after the lock timeout.
                    restore.rollback();
                }
            }
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // shutdownNow cannot interrupt a thread inside a JDBC call: wait until the launch has
            // ended, so it cannot change the database under a later test.
            executor.shutdown();
            if (!executor.awaitTermination(LAUNCH_END_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                AssertionError stuck = new AssertionError(
                        "the launch did not end within " + LAUNCH_END_WAIT_SECONDS + " s");
                if (failure == null) {
                    throw stuck;
                }
                failure.addSuppressed(stuck);
            }
        }
        assertThat(repository.getJobInstance(JOB, identifying(cycle, 1))).isNull();
        assertThat(cycleExecutions(cycle)).isZero();
    }

    private long openFixtureCycle() {
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card(CARD, "00000000001", "000000001");
        return openCycle(DATE);
    }

    /** A cycle COMPLETED at attempt 1 by its window's completing close. */
    private long completedCycle() {
        long cycle = openFixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);
        return cycle;
    }

    private static Map<String, Object> request(long cycle) {
        return Map.of("params", Map.of("cycleId", cycle));
    }

    private static JobParameters identifying(long cycle, int attempt) {
        return new JobParametersBuilder().addLong("cycleId", cycle, true)
                .addLocalDate("businessDate", LocalDate.parse(DATE), true).addLong("attempt", (long) attempt, true)
                .toJobParameters();
    }

    /**
     * An execution of {@code postDailyTransactionsJob}'s instance for the attempt, as a launch would
     * have left it; a running one without a lease row is recoverable once its LAST_UPDATED is older
     * than the leaseless limit (contract 4.4).
     */
    private long fixtureExecution(long cycle, int attempt, String status, int lastUpdatedOffsetSeconds) {
        JobInstance instance = repository.createJobInstance(JOB, identifying(cycle, attempt));
        long id = nextFake--;
        boolean running = "STARTED".equals(status);
        DB.admin().sql("""
                INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME,
                    START_TIME, END_TIME, LAST_UPDATED, STATUS, EXIT_CODE, EXIT_MESSAGE)
                VALUES (:id, 1, :instance, SYSUTCDATETIME(), SYSUTCDATETIME(), %s,
                    DATEADD(SECOND, :offset, SYSUTCDATETIME()), :status, :exit, '')"""
                .formatted(running ? "NULL" : "SYSUTCDATETIME()"))
                .param("id", id).param("instance", instance.getInstanceId()).param("offset", lastUpdatedOffsetSeconds)
                .param("status", status).param("exit", running ? "UNKNOWN" : status).update();
        parameter(id, "cycleId", "java.lang.Long", Long.toString(cycle), "Y");
        parameter(id, "businessDate", "java.time.LocalDate", DATE, "Y");
        parameter(id, "attempt", "java.lang.Long", Integer.toString(attempt), "Y");
        parameter(id, "expiryDays", "java.lang.Long", "5", "N");
        return id;
    }

    private static void parameter(long execution, String name, String type, String value, String identifying) {
        DB.admin().sql("""
                INSERT INTO batch.BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE,
                    PARAMETER_VALUE, IDENTIFYING) VALUES (:id, :name, :type, :value, :identifying)""")
                .param("id", execution).param("name", name).param("type", type).param("value", value)
                .param("identifying", identifying).update();
    }

    /** Stands in for a restore where a real rerun would refuse the fixture. */
    private static void raiseAttempt(long cycle) {
        DB.admin().sql("UPDATE batch.cycle SET attempt = attempt + 1 WHERE cycle_id = :c").param("c", cycle).update();
    }

    /** The context's JobLaunches, but its parameters are prepared before {@code hook} runs. */
    private JobLaunches afterPrepare(Runnable hook) {
        AtomicReference<Runnable> once = new AtomicReference<>(hook);
        Cycles hooked = new Cycles(db, transactions) {
            @Override
            public Optional<Cycle> find(long cycleId) {
                Optional<Cycle> read = super.find(cycleId);
                Runnable h = once.getAndSet(null);
                if (h != null) {
                    h.run();
                }
                return read;
            }
        };
        return launches(hooked, windows, jobsByName());
    }

    private BatchWindows heldWindows() {
        return new BatchWindows(db, transactions, cycles, catalog, properties, leases.owner()) {
            @Override
            public boolean heldBy(WindowHolder holder) {
                return true;
            }
        };
    }

    private JobLaunches launches(Cycles cycleReads, BatchWindows batchWindows, Map<String, Job> jobs) {
        return new JobLaunches(catalog, jobs, operator, repository, leases, recovery, batchWindows, cycleReads, db,
                dataSource, environment, properties);
    }

    private Map<String, Job> jobsByName() {
        return jobList.stream().collect(Collectors.toMap(Job::getName, Function.identity(), (a, b) -> a,
                java.util.HashMap::new));
    }

    private static InternalApiException refused(Supplier<?> launch) {
        InternalApiException refused = null;
        try {
            launch.get();
        } catch (InternalApiException e) {
            refused = e;
        }
        assertThat(refused).as("the launch's answer").isNotNull();
        return refused;
    }

    private static void assertCycleAnswer(InternalApiException answer, String code, int attempt, String status) {
        assertThat(answer.code()).isEqualTo(code);
        assertThat(answer.status().value()).isEqualTo(409);
        assertThat(answer.body()).containsEntry("attempt", attempt).containsEntry("status", status);
    }

    private static int attempt(long cycle) {
        return DB.admin().sql("SELECT attempt FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(Integer.class).single();
    }

    /** Every execution of every job that names the cycle. */
    private static long cycleExecutions(long cycle) {
        return count("SELECT COUNT_BIG(*) FROM batch.BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_NAME = 'cycleId'"
                + " AND PARAMETER_VALUE = ?", Long.toString(cycle));
    }

    private static long instanceExecutions(long execution) {
        return count("SELECT COUNT_BIG(*) FROM batch.BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID ="
                + " (SELECT JOB_INSTANCE_ID FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?)", execution);
    }

    private static String executionStatus(long execution) {
        return DB.admin().sql("SELECT STATUS FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", execution).query(String.class).single();
    }

    private static void awaitBlockedBy(int spid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (DB.admin().sql("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id = :s")
                .param("s", spid).query(Integer.class).single() == 0) {
            assertThat(System.nanoTime()).as("the launch waits for the launch lock").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static void holdLaunchLock(Connection session) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_getapplock @Resource = 'carddemo.batch_launch', @LockMode = 'Exclusive',"
                    + " @LockOwner = 'Session'");
        }
    }

    /** A pooled connection keeps its session, and with it a session lock, after close. */
    private static void releaseLaunchLock(Connection session) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_releaseapplock @Resource = 'carddemo.batch_launch', @LockOwner = 'Session'");
        }
    }
}
