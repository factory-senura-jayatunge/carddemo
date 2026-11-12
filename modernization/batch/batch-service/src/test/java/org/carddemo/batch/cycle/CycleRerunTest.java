package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.carddemo.batch.BatchServiceApplication;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.RequestTransactions;
import org.carddemo.batch.internal.BatchCommandCheck;
import org.carddemo.batch.internal.BatchWindows;
import org.carddemo.batch.internal.Cycle;
import org.carddemo.batch.internal.CycleReruns;
import org.carddemo.batch.internal.Cycles;
import org.carddemo.batch.internal.InternalApiException;
import org.carddemo.batch.internal.InternalApiProperties;
import org.carddemo.batch.internal.JobCatalog;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.batch.statement.StatementRunFixtures;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * cycle-rerun (contract section 7; batch.md, "Deliberate rerun"): the restore, a second run of the
 * cycle that ends as the first, and the refusals. The D-33 part is case 18 of
 * AuthorizationMatchingJobTest.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7"})
@ExtendWith(OutputCaptureExtension.class)
class CycleRerunTest extends CycleJobTestBase {

    private static final String DATE = "2101-06-02";
    private static final long FAKE_EXECUTION = -7;
    private static final List<String> REFERENCE_TABLES = List.of("ref.disclosure_group", "ref.tran_category",
            "ref.tran_type", "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    @Autowired
    CycleReruns reruns;

    @Autowired
    CycleRestore restore;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    @BeforeEach
    @AfterEach
    void emptySnapshotsAndTheFakeExecution() {
        StatementRunFixtures.drop(DB.admin(), STATEMENT_EXECUTION, LATER_STATEMENT_EXECUTION, EARLIER_STATEMENT_EXECUTION);
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        DB.admin().sql("DELETE FROM batch.auth_match_pair").update();
        DB.admin().sql("DELETE FROM batch.execution_lease WHERE job_execution_id = :id").param("id", FAKE_EXECUTION).update();
        DB.admin().sql("DELETE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id").param("id", FAKE_EXECUTION)
                .update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = :id")
                .param("id", FAKE_EXECUTION).update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id").param("id", FAKE_EXECUTION)
                .update();
        DB.admin().sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id").param("id", FAKE_EXECUTION)
                .update();
    }

    private void loadSample() throws Exception {
        for (String table : REFERENCE_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
        JobExecution load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private void runCycle(long cycle) {
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /** What the cycle leaves behind, without card numbers: balances, category balances, postings, rejects. */
    private static List<String> endState(long cycle) {
        List<String> state = new java.util.ArrayList<>();
        state.addAll(DB.admin().sql("SELECT CONCAT('account ', acct_id, ' ', curr_bal, ' ', curr_cyc_credit, ' ',"
                + " curr_cyc_debit) FROM core.account ORDER BY acct_id").query(String.class).list());
        state.addAll(DB.admin().sql("SELECT CONCAT('tcat ', acct_id, ' ', type_cd, cat_cd, ' ', balance, ' ',"
                + " COALESCE(legacy_filler, N'null')) FROM core.tcat_bal ORDER BY acct_id, type_cd, cat_cd")
                .query(String.class).list());
        state.addAll(DB.admin().sql("""
                SELECT CONCAT('tran ', tran_id, ' ', amount, ' ', type_cd, cat_cd, ' ', in_master, ' ', origin COLLATE Latin1_General_100_BIN2, ' ',
                    COALESCE(created_by, N'-'))
                FROM core.[transaction] WHERE cycle_id = :c ORDER BY tran_id""").param("c", cycle)
                .query(String.class).list());
        state.addAll(DB.admin().sql("SELECT CONCAT('reject ', seq, ' ', reason_cd) FROM core.daily_reject"
                + " WHERE cycle_id = :c ORDER BY seq").param("c", cycle).query(String.class).list());
        state.add("claimed " + count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ?", cycle));
        return state;
    }

    @Test
    void aRerunRestoresTheCycleAndItsNextRunEndsAsTheFirst() throws Exception {
        loadSample();
        List<String> lines = sampleDaily();
        byte[] file = dailyFile(DATE, lines);
        waitingAdd(withId(lines.get(1), "0000000000000999"));
        long cycle = openCycle(DATE);
        runCycle(cycle);
        List<String> first = endState(cycle);
        assertThat(first).anyMatch(s -> s.startsWith("tran ")).anyMatch(s -> s.startsWith("reject "));
        windows.close(WindowHolder.cycle(cycle), true);
        assertThat(Files.readAllBytes(inbound(DATE))).as("left inbound by the ingest").isEqualTo(file);

        CycleReruns.Rerun rerun = reruns.rerun(cycle);

        assertThat(rerun.cycle().status()).isEqualTo("OPEN");
        assertThat(rerun.cycle().attempt()).isEqualTo(2);
        assertThat(rerun.dailyFileReturned()).as("the inbound file is still there").isFalse();
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
        assertThat(rerun.restored().transactions()).isEqualTo(first.stream().filter(s -> s.startsWith("tran ")).count());
        assertThat(rerun.restored().rejects()).isEqualTo(first.stream().filter(s -> s.startsWith("reject ")).count());
        assertThat(rerun.restored().stagedRows()).isEqualTo(300);
        assertThat(rerun.restored().onlineAdds()).isEqualTo(1);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id IS NULL AND origin = 'ONLINE_ADD'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE cycle_id = ?", cycle)).isZero();
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM core.account AS a JOIN batch.snapshot_account AS s ON s.acct_id = a.acct_id
                WHERE s.cycle_id = ? AND (a.curr_bal <> s.curr_bal OR a.curr_cyc_credit <> s.curr_cyc_credit
                    OR a.curr_cyc_debit <> s.curr_cyc_debit)""", cycle)).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal")).isEqualTo(50);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.cycle WHERE cycle_id = ? AND daily_file_sha256 IS NULL", cycle))
                .as("the next run identifies the inbound file afresh").isEqualTo(1);
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("open for the next DAG run").isTrue();

        runCycle(cycle);

        assertThat(endState(cycle)).containsExactlyElementsOf(first);
        assertThat(Files.exists(root().resolve(ExportRejectsTasklet.relativePath(new CycleRun(cycle, date(DATE), 2)))))
                .isTrue();
        assertThat(names(archiveFolder())).containsExactly(".work", archived(DATE, 1, file).getFileName().toString(),
                archived(DATE, 2, file).getFileName().toString());
    }

    /** Contract section 4.9: a corrected inbound file is staged as the next attempt under its own hash. */
    @Test
    void aRerunWithACorrectedInboundFileStagesItAndKeepsTheFirstCopy() throws Exception {
        List<String> lines = sampleDaily();
        byte[] file = dailyFile(DATE, lines);
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> correctedLines = new java.util.ArrayList<>(lines);
        correctedLines.set(5, withId(lines.get(5), "C000000000000001"));
        byte[] corrected = dailyFile(DATE, correctedLines);

        CycleReruns.Rerun rerun = reruns.rerun(cycle);
        JobExecution second = launch("ingestDailyFileJob", cycle);

        assertThat(rerun.dailyFileReturned()).isFalse();
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(DB.admin().sql("SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c AND origin = 'DAILY_FILE'"
                + " ORDER BY seq").param("c", cycle).query(String.class).list()).containsExactlyElementsOf(correctedLines);
        assertThat(Files.readAllBytes(archived(DATE, 1, file))).isEqualTo(file);
        assertThat(Files.readAllBytes(archived(DATE, 2, corrected))).isEqualTo(corrected);
        assertThat(DB.admin().sql("SELECT daily_file_name FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(String.class).single()).isEqualTo(archived(DATE, 2, corrected).getFileName().toString());
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(corrected);
    }

    @Test
    void aRerunWithNoInboundFilePutsBackTheCopyTheReplacedAttemptStaged() throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Files.delete(inbound(DATE));

        CycleReruns.Rerun rerun = reruns.rerun(cycle);

        assertThat(rerun.dailyFileReturned()).isTrue();
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
        assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
        assertThat(Files.readAllBytes(archived(DATE, 1, file))).isEqualTo(file);
    }

    /** Two prepared returns at once: each has its own temporary name, and one file with the archived bytes results. */
    @Test
    void concurrentReturnsLeaveOneInboundFileWithTheArchivedBytes() throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        for (int round = 0; round < 5; round++) {
            Files.delete(inbound(DATE));
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<Boolean> call = () -> {
                    try (CycleRestore.PreparedFile prepared = restore.prepareDailyFile(cycle, date(DATE), 1)) {
                        start.await();
                        return prepared.link();
                    }
                };
                var first = pool.submit(call);
                var second = pool.submit(call);
                assertThat(List.of(first.get(), second.get())).as("round %d", round).containsOnlyOnce(true);
            } finally {
                pool.shutdownNow();
            }
            assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
            assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
        }
    }

    @Test
    void aChangedArchiveCopyIsNotPutBackAndRefusesTheRerun() throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Files.delete(inbound(DATE));
        Files.write(archived(DATE, 1, file), "changed\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        refusedAndKept(reruns, cycle, "DAILY_FILE_CHANGED");

        assertThat(attempt(cycle)).isEqualTo(1);
        assertThat(names(inbound(DATE).getParent())).as("no daily file and no temporary copy").isEmpty();
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("a window the cycle already held stays open").isTrue();
    }

    private long fixtureCycle() {
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card("9".repeat(12) + "0001", "00000000001", "000000001");
        return openCycle(DATE);
    }

    private static void fakeExecution(long cycle, String lastUpdatedOffsetSeconds) {
        DB.admin().sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)"
                + " VALUES (:id, 0, 'postDailyTransactionsJob', 'cycleRerunTest')").param("id", FAKE_EXECUTION).update();
        DB.admin().sql("""
                INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME,
                    START_TIME, LAST_UPDATED, STATUS)
                VALUES (:id, 1, :id, GETUTCDATE(), GETUTCDATE(), DATEADD(SECOND, %s, GETUTCDATE()), 'STARTED')"""
                .formatted(lastUpdatedOffsetSeconds)).param("id", FAKE_EXECUTION).update();
        DB.admin().sql("""
                INSERT INTO batch.BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE,
                    PARAMETER_VALUE, IDENTIFYING) VALUES (:id, 'cycleId', 'java.lang.Long', :c, 'Y')""")
                .param("id", FAKE_EXECUTION).param("c", Long.toString(cycle)).update();
    }

    private static int attempt(long cycle) {
        return DB.admin().sql("SELECT attempt FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(Integer.class).single();
    }

    @Test
    void aLiveExecutionOfTheCycleRefusesTheRerunBeforeTheWindowOpens() {
        long cycle = fixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);
        fakeExecution(cycle, "0");

        assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("EXECUTIONS_RUNNING");

        assertThat(attempt(cycle)).isEqualTo(1);
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).isFalse();
    }

    @Test
    void anExecutionWhoseOwnerIsLostIsRecoveredFirst() {
        long cycle = fixtureCycle();
        fakeExecution(cycle, "-600");

        CycleReruns.Rerun rerun = reruns.rerun(cycle);

        assertThat(rerun.recovered()).containsExactly(FAKE_EXECUTION);
        assertThat(rerun.dailyFileReturned()).as("the attempt staged no file").isFalse();
        assertThat(DB.admin().sql("SELECT STATUS FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", FAKE_EXECUTION).query(String.class).single()).isEqualTo("FAILED");
        assertThat(rerun.cycle().attempt()).isEqualTo(2);
    }

    @Test
    void aTransactionWrittenOutsideTheCycleAfterItsSnapshotRefusesTheRerun() {
        long cycle = fixtureCycle();
        DB.admin().sql("""
                UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME(),
                    snapshot_tran_pk = (SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction]) WHERE cycle_id = :c""")
                .param("c", cycle).update();
        windows.close(WindowHolder.cycle(cycle), true);
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin)
                VALUES ('0000000000000001', '02', '0002', 'POS TERM', 'BILL PAYMENT - ONLINE', 10.00, '999999999',
                    'BILL PAYMENT', 'N/A', 'N/A', :card, SYSDATETIME(), SYSDATETIME(), 'BILL_PAY')""")
                .param("card", "9".repeat(12) + "0001").update();

        assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("WRITTEN_SINCE_SNAPSHOT");

        assertThat(attempt(cycle)).isEqualTo(1);
        assertThat(DB.admin().sql("SELECT status FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("closed again").isFalse();
    }

    @Test
    void aLaterCycleThatStartedRefusesTheRerun() {
        long cycle = fixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);
        DB.admin().sql("INSERT INTO batch.cycle (business_date, snapshot_at) VALUES ('2101-06-03', SYSUTCDATETIME())")
                .update();

        assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("LATER_CYCLE_STARTED");
        assertThat(attempt(cycle)).isEqualTo(1);
    }

    /** The one-shot command as make cycle-rerun starts it, and the exit code it returns. */
    private int command(long cycle) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BatchServiceApplication.class)
                .listeners(new BatchCommandCheck()).run(
                "--carddemo.batch.command=cycle-rerun",
                "--carddemo.batch.rerun.cycle-id=" + cycle,
                "--carddemo.var-dir=" + roots.varDir(),
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.datasource.username=" + DB.loginName(ServiceLogin.BATCH),
                "--spring.datasource.password=" + DB.password(ServiceLogin.BATCH));
        return SpringApplication.exit(context);
    }

    @Test
    void theCommandExitsOneOnEachRefusalAndZeroOnceItRestores() {
        long cycle = fixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);

        DB.admin().sql("INSERT INTO batch.cycle (business_date, snapshot_at) VALUES ('2101-06-03', SYSUTCDATETIME())")
                .update();
        assertThat(command(cycle)).as("LATER_CYCLE_STARTED").isEqualTo(1);
        DB.admin().sql("DELETE FROM batch.cycle WHERE business_date = '2101-06-03'").update();

        DB.admin().sql("""
                UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME(),
                    snapshot_tran_pk = (SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction]) WHERE cycle_id = :c""")
                .param("c", cycle).update();
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin)
                VALUES ('0000000000000001', '02', '0002', 'POS TERM', 'BILL PAYMENT - ONLINE', 10.00, '999999999',
                    'BILL PAYMENT', 'N/A', 'N/A', :card, SYSDATETIME(), SYSDATETIME(), 'BILL_PAY')""")
                .param("card", "9".repeat(12) + "0001").update();
        assertThat(command(cycle)).as("WRITTEN_SINCE_SNAPSHOT").isEqualTo(1);
        assertThat(command(cycle + 1000)).as("CYCLE_NOT_FOUND").isEqualTo(1);
        assertThat(attempt(cycle)).isEqualTo(1);
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("closed again").isFalse();

        DB.admin().sql("DELETE FROM core.[transaction] WHERE tran_id = '0000000000000001'").update();
        assertThat(command(cycle)).isZero();
        assertThat(attempt(cycle)).isEqualTo(2);
    }

    private static final long STATEMENT_EXECUTION = -8;
    private static final long LATER_STATEMENT_EXECUTION = -9;
    private static final long EARLIER_STATEMENT_EXECUTION = -10;

    private static long cycleOn(String businessDate) {
        return DB.admin().sql("INSERT INTO batch.cycle (business_date) OUTPUT inserted.cycle_id VALUES (:d)")
                .param("d", businessDate).query(Long.class).single();
    }

    /**
     * A later cycle that has not started (so LATER_CYCLE_STARTED does not apply) but has a
     * COMPLETED statement run refuses the rerun with LATER_STATEMENT_COMPLETED and changes nothing
     * (batch.md, "Runs, restart and rerun"). The message has counts only.
     */
    @Test
    void aLaterCompletedStatementRunRefusesTheRerunAfterTheOtherChecks() {
        long cycle = fixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);
        long later = cycleOn("2101-06-03");
        long laterRun = StatementRunFixtures.insert(DB.admin(), later, "COMPLETED", LATER_STATEMENT_EXECUTION);

        assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("LATER_STATEMENT_COMPLETED")
                .hasMessageContaining("1 later cycle(s) have completed statements");
        assertThat(attempt(cycle)).isEqualTo(1);
        assertThat(StatementRunFixtures.status(DB.admin(), laterRun)).isEqualTo("COMPLETED");
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("closed again").isFalse();

        // The existing refusals come first.
        DB.admin().sql("UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME() WHERE cycle_id = :c").param("c", later)
                .update();
        assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("LATER_CYCLE_STARTED");
        DB.admin().sql("UPDATE batch.cycle SET snapshot_at = NULL WHERE cycle_id = :c").param("c", later).update();

        // A superseded later run issued nothing that still stands.
        DB.admin().sql("UPDATE batch.statement_run SET status = 'SUPERSEDED' WHERE statement_run_id = :r")
                .param("r", laterRun).update();
        assertThat(reruns.rerun(cycle).cycle().attempt()).isEqualTo(2);
    }

    @Test
    void theCommandExitsOneOnALaterCompletedStatementRun() {
        long cycle = fixtureCycle();
        windows.close(WindowHolder.cycle(cycle), true);
        StatementRunFixtures.insert(DB.admin(), cycleOn("2101-06-03"), "COMPLETED", LATER_STATEMENT_EXECUTION);

        assertThat(command(cycle)).as("LATER_STATEMENT_COMPLETED").isEqualTo(1);
        assertThat(attempt(cycle)).isEqualTo(1);
    }

    /**
     * The restore supersedes the cycle's COMPLETED run (its folder stays as archive) and invalidates
     * every unfinished run of this cycle and of the later ones; an earlier cycle's runs are kept.
     */
    @Test
    void aRerunSupersedesTheCyclesCompletedRunAndInvalidatesUnfinishedRuns() {
        long earlier = cycleOn("2101-06-01");
        long earlierRun = StatementRunFixtures.insert(DB.admin(), earlier, "FAILED", EARLIER_STATEMENT_EXECUTION);
        long cycle = fixtureCycle();
        long run = StatementRunFixtures.insert(DB.admin(), cycle, "COMPLETED", STATEMENT_EXECUTION);
        long laterRun = StatementRunFixtures.insert(DB.admin(), cycleOn("2101-06-03"), "PUBLISHING",
                LATER_STATEMENT_EXECUTION);

        assertThat(reruns.rerun(cycle).cycle().attempt()).isEqualTo(2);

        assertThat(StatementRunFixtures.status(DB.admin(), run)).isEqualTo("SUPERSEDED");
        assertThat(StatementRunFixtures.status(DB.admin(), laterRun)).isEqualTo("INVALIDATED");
        assertThat(StatementRunFixtures.status(DB.admin(), earlierRun)).isEqualTo("FAILED");
    }

    private static final String CARD = "9".repeat(12) + "0001";
    private static final InternalApiProperties QUICK = new InternalApiProperties(null, Duration.ofSeconds(60),
            Duration.ofSeconds(120), Duration.ofSeconds(1));

    @Autowired
    JdbcClient db;

    @Autowired
    RequestTransactions transactions;

    @Autowired
    JobCatalog catalog;

    @Autowired
    ExecutionRecovery recovery;

    @Autowired
    ExecutionLeases leases;

    /** What a refusal must leave as it was, for the cycle and the tables a restore changes. */
    private record Kept(int attempt, String identity, long snapshots, long transactions, long rejects, long staged) { }

    private static Kept kept(long cycle) {
        return new Kept(attempt(cycle),
                DB.admin().sql("SELECT CONCAT(daily_file_name, ' ', CONVERT(VARCHAR(64), daily_file_sha256, 2))"
                        + " FROM batch.cycle WHERE cycle_id = :c").param("c", cycle).query(String.class).single(),
                count("SELECT COUNT_BIG(*) FROM batch.snapshot_account"),
                count("SELECT COUNT_BIG(*) FROM core.[transaction]"),
                count("SELECT COUNT_BIG(*) FROM core.daily_reject"),
                count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ?", cycle));
    }

    /** Asserts the refusal's code, that it changed nothing, and that the inbound folder is empty. */
    private InternalApiException refusedAndKept(CycleReruns rerun, long cycle, String code) {
        Kept before = kept(cycle);
        InternalApiException refused = null;
        try {
            rerun.rerun(cycle);
        } catch (InternalApiException e) {
            refused = e;
        }
        assertThat(refused).as("%s refusal", code).isNotNull();
        assertThat(refused.code()).isEqualTo(code);
        assertThat(kept(cycle)).as("%s changes nothing", code).isEqualTo(before);
        assertThat(names(inbound(DATE).getParent())).as("%s: no daily file and no temporary copy inbound", code)
                .isEmpty();
        return refused;
    }

    /**
     * cycle-rerun from the context's beans with two seams: while {@code stale} is set, its reads of
     * the cycle are one attempt behind, as after another rerun; while {@code skipRecovery} is set,
     * it recovers nothing. Launch-lock waits are 1 s.
     */
    private CycleReruns seamed(AtomicBoolean stale, AtomicBoolean skipRecovery) {
        Cycles behind = new Cycles(db, transactions) {
            @Override
            public Optional<Cycle> find(long cycleId) {
                return super.find(cycleId).map(c -> stale.get() ? new Cycle(c.cycleId(), c.businessDate(),
                        c.attempt() - 1, c.interestParm(), c.status(), c.openedAt(), c.closedAt()) : c);
            }
        };
        ExecutionRecovery skipping = new ExecutionRecovery(null, null, null) {
            @Override
            public Outcome recover(long executionId) {
                return skipRecovery.get() ? new Outcome.NotRecoverable() : recovery.recover(executionId);
            }
        };
        BatchWindows quick = new BatchWindows(db, transactions, cycles, catalog, QUICK, leases.owner());
        return new CycleReruns(db, transactions, behind, quick, catalog, skipping, restore, QUICK);
    }

    private static void snapshotAndBillPayment(long cycle) {
        DB.admin().sql("""
                UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME(),
                    snapshot_tran_pk = (SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction]) WHERE cycle_id = :c""")
                .param("c", cycle).update();
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin)
                VALUES ('0000000000000001', '02', '0002', 'POS TERM', 'BILL PAYMENT - ONLINE', 10.00, '999999999',
                    'BILL PAYMENT', 'N/A', 'N/A', :card, SYSDATETIME(), SYSDATETIME(), 'BILL_PAY')""")
                .param("card", CARD).update();
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

    /**
     * Before the fix the staged file went back before the transaction's checks, so a refused rerun
     * left it inbound. Now a refusal after the return was prepared puts nothing back.
     */
    @Test
    void aRefusalAfterTheReturnWasPreparedPutsNothingBack() throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        windows.close(WindowHolder.cycle(cycle), true);
        Files.delete(inbound(DATE));
        DB.admin().sql("INSERT INTO batch.cycle (business_date, snapshot_at) VALUES ('2101-06-03', SYSUTCDATETIME())")
                .update();

        refusedAndKept(reruns, cycle, "LATER_CYCLE_STARTED");
        assertThat(windows.heldBy(WindowHolder.cycle(cycle))).as("closed again").isFalse();

        DB.admin().sql("DELETE FROM batch.cycle WHERE business_date = '2101-06-03'").update();
        CycleReruns.Rerun rerun = reruns.rerun(cycle);

        assertThat(rerun.dailyFileReturned()).isTrue();
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
        assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
    }

    /**
     * Every refusal inside the restore's transaction holds at once; removed one by one, they come in
     * the documented order, and none changes the cycle, its tables or the inbound folder. A refusal
     * keeps the recovery it made. When the close after a refusal fails, the window stays open and a
     * warning says so.
     */
    @Test
    void theRefusalsComeInOrderAndNoneChangesAnything(CapturedOutput output) throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = fixtureCycle();
        WindowHolder holder = WindowHolder.cycle(cycle);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        windows.close(holder, true);
        Files.delete(inbound(DATE));
        Path archive = archived(DATE, 1, file);
        Files.write(archive, "changed\n".getBytes(StandardCharsets.ISO_8859_1));
        fakeExecution(cycle, "-600");
        snapshotAndBillPayment(cycle);
        long later = cycleOn("2101-06-03");
        DB.admin().sql("UPDATE batch.cycle SET snapshot_at = SYSUTCDATETIME() WHERE cycle_id = :c").param("c", later)
                .update();
        long laterRun = StatementRunFixtures.insert(DB.admin(), later, "COMPLETED", LATER_STATEMENT_EXECUTION);
        AtomicBoolean stale = new AtomicBoolean(true);
        AtomicBoolean skipRecovery = new AtomicBoolean(true);
        CycleReruns seamed = seamed(stale, skipRecovery);

        try (Connection session = DB.adminDataSource().getConnection()) {
            holdLaunchLock(session);
            try {
                refusedAndKept(seamed, cycle, "LAUNCH_IN_PROGRESS");
                assertThat(windows.heldBy(holder)).as("the close after the refusal waits for the same lock").isTrue();
            } finally {
                releaseLaunchLock(session);
            }
        }
        assertThat(output).contains("cycle-rerun: the batch window of cycle " + cycle
                + " stays open; closing it after the refusal failed: LAUNCH_IN_PROGRESS");
        windows.close(holder, false);

        refusedAndKept(seamed, cycle, "CYCLE_CHANGED");
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        stale.set(false);

        refusedAndKept(seamed, cycle, "DAILY_FILE_CHANGED");
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        Files.write(archive, file);

        InternalApiException running = refusedAndKept(seamed, cycle, "EXECUTIONS_RUNNING");
        assertThat(running.body()).containsEntry("executionIds", List.of(FAKE_EXECUTION));
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        skipRecovery.set(false);

        refusedAndKept(seamed, cycle, "LATER_CYCLE_STARTED");
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        assertThat(DB.admin().sql("SELECT CONCAT(STATUS, ' ', EXIT_MESSAGE) FROM batch.BATCH_JOB_EXECUTION"
                        + " WHERE JOB_EXECUTION_ID = :id").param("id", FAKE_EXECUTION).query(String.class).single())
                .as("the refusal keeps the recovery").startsWith("FAILED OWNER_LOST");
        DB.admin().sql("UPDATE batch.cycle SET snapshot_at = NULL WHERE cycle_id = :c").param("c", later).update();

        refusedAndKept(seamed, cycle, "WRITTEN_SINCE_SNAPSHOT");
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        DB.admin().sql("DELETE FROM core.[transaction] WHERE tran_id = '0000000000000001'").update();

        refusedAndKept(seamed, cycle, "LATER_STATEMENT_COMPLETED");
        assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        assertThat(StatementRunFixtures.status(DB.admin(), laterRun)).isEqualTo("COMPLETED");
        DB.admin().sql("UPDATE batch.statement_run SET status = 'SUPERSEDED' WHERE statement_run_id = :r")
                .param("r", laterRun).update();

        CycleReruns.Rerun rerun = seamed.rerun(cycle);

        assertThat(rerun.cycle().attempt()).isEqualTo(2);
        assertThat(rerun.recovered()).as("recovered by the earlier refused rerun").isEmpty();
        assertThat(rerun.dailyFileReturned()).isTrue();
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
        assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
        assertThat(windows.heldBy(holder)).as("open for the next DAG run").isTrue();
    }

    /** The refusals before the window opens: another holder's window, and an unknown cycle. */
    @Test
    void anotherHoldersWindowOrAnUnknownCycleRefusesTheRerunAndChangesNothing() {
        long cycle = fixtureCycle();
        WindowHolder holder = WindowHolder.cycle(cycle);
        windows.close(holder, true);
        WindowHolder other = WindowHolder.run("carddemo_test/rerun-busy");
        windows.open(other);
        try {
            refusedAndKept(reruns, cycle, "WINDOW_BUSY");
            assertThat(windows.heldBy(holder)).isFalse();
            assertThat(windows.heldBy(other)).as("the other holder's window stays open").isTrue();
        } finally {
            windows.close(other, false);
        }

        Kept before = kept(cycle);
        assertThatThrownBy(() -> reruns.rerun(cycle + 1000)).isInstanceOfSatisfying(InternalApiException.class,
                e -> assertThat(e.code()).isEqualTo("CYCLE_NOT_FOUND"));
        assertThat(kept(cycle)).isEqualTo(before);
        assertThat(windows.heldBy(WindowHolder.cycle(cycle + 1000))).isFalse();
        assertThat(names(inbound(DATE).getParent())).isEmpty();
    }

    /**
     * An I/O failure once the temporary copy exists: the archive copy cannot be read (mode
     * {@code 000}), and the work file is created before the archive is opened. The preparation
     * removes its own copy before it throws, and the rerun that hits it changes nothing and closes
     * its window again. POSIX only, and not as root, who reads a mode-000 file.
     */
    @Test
    void anIoFailureWhilePreparingTheReturnLeavesNoTemporaryCopy() throws Exception {
        byte[] file = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        WindowHolder holder = WindowHolder.cycle(cycle);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        windows.close(holder, true);
        Files.delete(inbound(DATE));
        Path archive = archived(DATE, 1, file);
        assumeTrue(archive.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(archive), "this process reads a mode-000 file (root)");

            assertThatThrownBy(() -> restore.prepareDailyFile(cycle, date(DATE), 1))
                    .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(AccessDeniedException.class);
            assertThat(names(inbound(DATE).getParent())).as("no daily file and no temporary copy").isEmpty();

            Kept before = kept(cycle);
            assertThatThrownBy(() -> reruns.rerun(cycle)).isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(AccessDeniedException.class);
            assertThat(kept(cycle)).isEqualTo(before);
            assertThat(names(inbound(DATE).getParent())).as("no daily file and no temporary copy").isEmpty();
            assertThat(windows.heldBy(holder)).as("closed again").isFalse();
        } finally {
            Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-------"));
        }

        CycleReruns.Rerun rerun = reruns.rerun(cycle);

        assertThat(rerun.dailyFileReturned()).isTrue();
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(file);
        assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
    }
}
