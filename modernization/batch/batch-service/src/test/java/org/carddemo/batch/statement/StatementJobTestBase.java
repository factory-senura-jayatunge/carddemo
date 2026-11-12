package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.BatchWindows;
import org.carddemo.batch.internal.Cycles;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * statementJob against the module's test database as batch_svc, launched as Airflow launches it:
 * the sample loaded by dataLoadJob, a registered cycle with its window open, the sample day
 * ingested and posted, then statementJob through JobLaunches.
 */
abstract class StatementJobTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path SAMPLE_DIR = StatementGoldens.SAMPLE_DIR;
    static final String DATE = "2101-06-01";

    private static final List<String> REFERENCE_TABLES = List.of("ref.disclosure_group", "ref.tran_category",
            "ref.tran_type", "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    @Autowired
    Cycles cycles;

    @Autowired
    BatchWindows windows;

    @Autowired
    JobLaunches launches;

    @Autowired
    JobRepository repository;

    @Autowired
    OutputRoots roots;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    /** Empty tables and folders (the instance marker kept), then the sample loaded. */
    @BeforeEach
    void loadSample() throws Exception {
        DB.fixtures().deleteAll();
        Path root = root();
        for (Path dir : List.of(roots.inbound(), root)) {
            if (Files.exists(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir))
                            .filter(p -> !p.equals(root.resolve(".instance"))).forEach(p -> p.toFile().delete());
                }
            }
        }
        roots.ensureAtStart();
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        for (String table : REFERENCE_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
        JobExecution load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    Path root() {
        return roots.currentRoot().orElseThrow();
    }

    /** A registered cycle without interest, with its window open. */
    long openCycle(String businessDate) {
        long cycleId = cycles.register(Map.of("businessDate", businessDate, "interest", "never")).cycle().cycleId();
        windows.open(WindowHolder.cycle(cycleId));
        return cycleId;
    }

    /** The sample day ingested and posted in a new cycle: 262 posted, 38 rejected (the CBTRN02C spec). */
    long postedSampleCycle() {
        byte[] content;
        try {
            content = Files.readAllBytes(SAMPLE_DIR.resolve("dailytran.txt"));
            Path inbound = roots.inbound().resolve("dailytran").resolve(DATE + ".txt");
            Files.createDirectories(inbound.getParent());
            Files.write(inbound, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE in_master = 1")).isEqualTo(262);
        return cycle;
    }

    /** Launches (or restarts) a cycle job and returns the execution as the repository holds it once it ended. */
    JobExecution launch(String jobName, long cycleId) {
        long id = launches.launch(jobName, Map.of("params", Map.of("cycleId", cycleId))).execution().getId();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        JobExecution execution = repository.getJobExecution(id);
        while (execution.getStatus().isRunning() || execution.getEndTime() == null) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("job execution " + id + " of " + jobName + " did not end");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            execution = repository.getJobExecution(id);
        }
        return execution;
    }

    /**
     * Sets an ended execution to FAILED in the job repository, the state a crash after a step's
     * commit leaves once recovery ran, so that the next launch restarts the instance.
     */
    static void markFailed(JobExecution execution) {
        DB.admin().sql("""
                UPDATE batch.BATCH_JOB_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'
                WHERE JOB_EXECUTION_ID = :id""").param("id", execution.getId()).update();
    }

    static long count(String sql, Object... args) {
        var spec = DB.admin().sql(sql);
        for (int i = 0; i < args.length; i++) {
            spec = spec.param(i + 1, args[i]);
        }
        return spec.query(Long.class).single();
    }

    static List<String> names(Path folder) {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The final folder of the cycle's first attempt. */
    Path finalFolder(long cycle) {
        return StatementFolders.statements(root()).resolve(cycle + "-1");
    }

    /** The working folder of {@code execution} for the cycle's first attempt. */
    Path workFolder(long cycle, long execution) {
        return StatementFolders.workRoot(root()).resolve(cycle + "-1." + execution);
    }

    /** The final folder's names and hashes for a working folder's: its files, its manifest and the claim. */
    static SortedMap<String, String> published(SortedMap<String, String> work) {
        SortedMap<String, String> expected = new TreeMap<>(work);
        expected.put(FolderPublication.CLAIM, work.get(FolderPublication.MANIFEST));
        return expected;
    }

    /** Exactly one final folder, and no working folder left. */
    void assertOneFinalFolder(long cycle) {
        assertThat(names(StatementFolders.statements(root()))).containsExactly(StatementFolders.WORK, cycle + "-1");
        assertThat(names(StatementFolders.workRoot(root()))).isEmpty();
    }

    /** The cycle's first-attempt run row, without timestamps. */
    static Map<String, Object> runRow(long cycle) {
        return DB.admin().sql("""
                SELECT statement_run_id, mode, status, watermark_tran_pk, previous_run_id, folder, work_folder,
                    manifest_sha256, failure_code, statements, transactions, unassigned, adjusted, job_execution_id
                FROM batch.statement_run WHERE cycle_id = :c AND attempt = 1""").param("c", cycle).query().singleRow();
    }

    /** The cycle's first-attempt run was captured in {@code mode}, the mode the test runs in. */
    static void assertRunMode(long cycle, FidelityMode mode) {
        assertThat(runRow(cycle).get("mode")).as("the run's mode").isEqualTo(mode.name());
    }

    static long runId(long cycle) {
        return ((Number) runRow(cycle).get("statement_run_id")).longValue();
    }

    /** A run's account rows by account, every column but none that a clock sets. */
    static Map<String, Map<String, Object>> accountRows(long runId) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        DB.admin().sql("""
                SELECT acct_id, period_start, period_end, opening_balance, closing_balance, net_amount, interest_amount,
                    adjustment_amount, minimum_payment, credit_limit, tran_count, file_name, file_sha256
                FROM batch.account_statement WHERE statement_run_id = :r ORDER BY acct_id""").param("r", runId)
                .query().listOfRows().forEach(row -> rows.put((String) row.get("acct_id"), row));
        return rows;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every entry of {@code folder} by name with the SHA-256 of its bytes. */
    static SortedMap<String, String> hashes(Path folder) {
        SortedMap<String, String> hashes = new TreeMap<>();
        for (String name : names(folder)) {
            try {
                hashes.put(name, sha256(Files.readAllBytes(folder.resolve(name))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return hashes;
    }

    /** Every entry of {@code folder} as a replacement would change it. */
    static SortedMap<String, FileState> states(Path folder) {
        SortedMap<String, FileState> states = new TreeMap<>();
        for (String name : names(folder)) {
            states.put(name, FileState.of(folder.resolve(name)));
        }
        return states;
    }

    /** Flips the first bit of {@code file} in place, as a disk fault or a manual edit would. */
    static void flipFirstBit(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer first = ByteBuffer.allocate(1);
            channel.read(first, 0);
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (first.get(0) ^ 1)}), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A published file as a replacement would change it: its bytes, its inode and its modification time. */
    record FileState(String sha256, Object inode, FileTime modified) {

        static FileState of(Path file) {
            try {
                return new FileState(StatementJobTestBase.sha256(Files.readAllBytes(file)),
                        Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS),
                        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
