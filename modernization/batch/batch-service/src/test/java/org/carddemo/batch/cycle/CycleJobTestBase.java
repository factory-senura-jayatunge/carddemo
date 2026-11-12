package org.carddemo.batch.cycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.BatchWindows;
import org.carddemo.batch.internal.Cycles;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The cycle jobs against the module's test database as batch_svc, launched as Airflow launches
 * them: a registered cycle, its window open, POST-equivalent launches through JobLaunches.
 */
abstract class CycleJobTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    /**
     * Empties the tables, the inbound folder and the instance root but its marker, then makes the
     * root's output folders again as a start does.
     */
    @BeforeEach
    void emptyTables() throws IOException {
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
    }

    /** The current instance's root, which the context's start made. */
    Path root() {
        return roots.currentRoot().orElseThrow();
    }

    /** A registered cycle without interest, with its window open. */
    long openCycle(String businessDate) {
        return openCycle(businessDate, "never");
    }

    /** A registered cycle with the given interest decision (always, month_end or never), with its window open. */
    long openCycle(String businessDate, String interest) {
        long cycleId = cycles.register(Map.of("businessDate", businessDate, "interest", interest)).cycle().cycleId();
        windows.open(WindowHolder.cycle(cycleId));
        return cycleId;
    }

    /** Launches (or restarts) a cycle job; without an async executor the launch returns once it ended. */
    JobExecution launch(String jobName, long cycleId) {
        long id = launches.launch(jobName, Map.of("params", Map.of("cycleId", cycleId))).execution().getId();
        return repository.getJobExecution(id);
    }

    static List<String> sampleDaily() {
        try {
            return new ArrayList<>(Files.readAllLines(SAMPLE_DIR.resolve("dailytran.txt"), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String withId(String line, String tranId) {
        return tranId + line.substring(16);
    }

    /** The inbound daily file in this class's private CARDDEMO_VAR_DIR (O8). */
    Path inbound(String businessDate) {
        return roots.inbound().resolve("dailytran").resolve(businessDate + ".txt");
    }

    /** The archive copy that an attempt staging {@code content} publishes (contract section 4.9). */
    Path archived(String businessDate, int attempt, byte[] content) {
        return archiveFolder().resolve(businessDate + "." + attempt + "." + HexFormat.of().formatHex(DailyFiles.sha256(content))
                + ".txt");
    }

    Path archiveFolder() {
        return root().resolve("dailytran").resolve("archive");
    }

    /** The names in a folder, sorted; empty when it does not exist. */
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

    byte[] dailyFile(String businessDate, List<String> lines) {
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        try {
            Files.createDirectories(inbound(businessDate).getParent());
            Files.write(inbound(businessDate), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return content;
    }

    /** An online add waiting for a cycle (D-27), its raw record in the daily layout as transaction-service builds it. */
    static void waitingAdd(String line) {
        FixedRecord r = FixedRecord.of(Layouts.DAILY_TRAN, line.getBytes(StandardCharsets.ISO_8859_1));
        DB.admin().sql("""
                INSERT INTO core.daily_tran (origin, created_by, tran_id, type_cd, cat_cd, source, description, amount,
                    merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, raw_record)
                VALUES ('ONLINE_ADD', 'USER0001', :id, :type, :cat, :source, :desc, :amt, :mid, :mname, :mcity, :mzip,
                    :card, :orig, :proc, :raw)""")
                .param("id", r.raw("DALYTRAN-ID")).param("type", r.raw("DALYTRAN-TYPE-CD"))
                .param("cat", r.raw("DALYTRAN-CAT-CD")).param("source", r.text("DALYTRAN-SOURCE"))
                .param("desc", r.text("DALYTRAN-DESC")).param("amt", r.decimal("DALYTRAN-AMT"))
                .param("mid", r.raw("DALYTRAN-MERCHANT-ID")).param("mname", r.text("DALYTRAN-MERCHANT-NAME"))
                .param("mcity", r.text("DALYTRAN-MERCHANT-CITY")).param("mzip", r.text("DALYTRAN-MERCHANT-ZIP"))
                .param("card", r.raw("DALYTRAN-CARD-NUM")).param("orig", r.raw("DALYTRAN-ORIG-TS"))
                .param("proc", r.raw("DALYTRAN-PROC-TS")).param("raw", line).update();
    }

    static long count(String sql, Object... args) {
        var spec = DB.admin().sql(sql);
        for (int i = 0; i < args.length; i++) {
            spec = spec.param(i + 1, args[i]);
        }
        return spec.query(Long.class).single();
    }

    static LocalDate date(String d) {
        return LocalDate.parse(d);
    }
}
