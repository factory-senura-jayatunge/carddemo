package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DEF-ACUP-002 (ruling v4) with a supplied layout manifest, in fixed mode, the ledger's: the
 * manifest binds by the file's SHA-256 only, declares the layout of each record, and is checked in
 * full before anything is written. The hashes of the bytes read are kept for the job instance; a
 * restart that reads other bytes fails.
 */
@SpecItem("DEF-ACUP-002")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadLayoutManifestTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();
    private static final Path MANIFEST = WORK_DIR.resolveSibling(WORK_DIR.getFileName() + ".layout.toml");
    /** ACCT-GROUP-ID, positions 113-122 of the 300-byte record. */
    private static final int GROUP_FROM = 112;
    private static final int GROUP_TO = 122;

    @Autowired
    FidelitySwitches switches;

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
        registry.add("carddemo.data-load.account-layout-manifest", MANIFEST::toString);
    }

    private static Path workDir() {
        try {
            return Files.createTempDirectory("carddemo-data-load-layout");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void sample() {
        assertThat(switches.mode(BatchSwitches.DEF_ACUP_002)).as("the ledger's mode").isEqualTo(FidelityMode.FIXED);
        sampleWith(WORK_DIR, "acctdata.txt", lines -> lines);
    }

    @AfterAll
    static void removeWorkDir() throws IOException {
        Files.deleteIfExists(MANIFEST);
        try (Stream<Path> files = Files.walk(WORK_DIR)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static Path acctdata() {
        return WORK_DIR.resolve("acctdata.txt");
    }

    /** The sample with a group ID at its own position on line 3 (account 3). */
    private static void groupOnLine3() {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(2);
            lines.set(2, l.substring(0, GROUP_FROM) + "B000000000" + l.substring(GROUP_TO));
            return lines;
        });
    }

    private static ExecutionContext loadAccounts(JobExecution execution) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("loadAccounts"))
                .findFirst().orElseThrow().getExecutionContext();
    }

    private static List<Map<String, Object>> origins() {
        return DB.admin().sql("SELECT zip_group_origin, COUNT(*) AS n FROM core.account GROUP BY zip_group_origin"
                + " ORDER BY zip_group_origin").query().listOfRows();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    void aSuppliedManifestReplacesTheBuiltInAndIsReported() throws Exception {
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(origins()).containsExactly(Map.of("zip_group_origin", "M", "n", 50));
        ExecutionContext report = loadAccounts(execution);
        assertThat(report.getString("accountLayoutSource")).isEqualTo("supplied");
        assertThat(report.getLong("accountsMigrated")).isEqualTo(50);
        assertThat(report.getString("accountLayoutManifestSha256")).isEqualTo(sha256(MANIFEST));
        assertThat(execution.getExecutionContext().getString("dataLoad.accountLayoutManifestSha256"))
                .isEqualTo(sha256(MANIFEST));
        assertThat(execution.getExecutionContext().getString("dataLoad.acctdataSha256")).isEqualTo(sha256(acctdata()));
    }

    @Test
    void anOverrideStoresItsRecordInTheOtherLayout() throws Exception {
        groupOnLine3();
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE", "00000000003");

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(origins()).containsExactly(Map.of("zip_group_origin", "M", "n", 49),
                Map.of("zip_group_origin", "R", "n", 1));
        assertThat(DB.admin().sql("SELECT addr_zip, RTRIM(group_id) AS group_id FROM core.account"
                + " WHERE zip_group_origin = 'R'").query().singleRow())
                .isEqualTo(Map.of("addr_zip", "A000000000", "group_id", "B000000000"));
        assertThat(loadAccounts(execution).getLong("accountLayoutWarnings")).isZero();
    }

    @Test
    void anUpdateLayoutRecordWithAGroupIdFailsBeforeAnythingIsWritten() throws Exception {
        groupOnLine3();
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution))
                .contains("acctdata.txt line 3: declared ACUP_UPDATE but positions 113-122 are not blank")
                .doesNotContain("00000000003");
        assertThat(count("core.account")).isZero();
    }

    @Test
    void aManifestForOtherBytesOrAccountsOrNoneAtAllIsRefused() throws Exception {
        Map<String, String> cases = Map.of(
                "layout manifest does not match acctdata.txt", "other bytes",
                "layout manifest override 1: not in acctdata.txt", "unknown account",
                "layout manifest override 2: listed twice", "listed twice",
                "layout manifest line 2: sha256 must be 64 lowercase hex digits", "bad hash",
                "layout manifest not found: " + MANIFEST, "missing");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Files.deleteIfExists(MANIFEST);
            switch (c.getValue()) {
                case "other bytes" -> {
                    groupOnLine3();
                    writeLayoutManifest(MANIFEST, acctdata(), "CVACT01Y");
                    sampleWith(WORK_DIR, "acctdata.txt", lines -> lines);
                }
                case "unknown account" -> writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE", "99999999999");
                case "listed twice" -> writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE", "00000000001",
                        "00000000001");
                case "bad hash" -> Files.writeString(MANIFEST, "schema = 1\nsha256 = \"ABC\"\ndefault_layout = \"CVACT01Y\"\n",
                        StandardCharsets.US_ASCII);
                default -> {
                }
            }

            JobExecution execution = run();

            assertThat(execution.getStatus()).as(c.getValue()).isEqualTo(BatchStatus.FAILED);
            assertThat(failures(execution)).as(c.getValue()).contains(c.getKey()).doesNotContain("99999999999");
            assertThat(count("core.account")).as(c.getValue()).isZero();
        }
    }

    @Test
    void aRestartThatReadsOtherBytesFailsWithSourceFileChanged() throws Exception {
        groupOnLine3();
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");
        JobExecution first = run();
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);

        // The manifest is corrected: the job instance keeps the hash of the first one.
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE", "00000000003");
        JobExecution manifestChanged = jobOperator.restart(first);
        assertThat(manifestChanged.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(manifestChanged)).contains(
                "SOURCE_FILE_CHANGED: the layout manifest differs from the first attempt of this job instance");

        // The file is corrected instead.
        sampleWith(WORK_DIR, "acctdata.txt", lines -> lines);
        JobExecution fileChanged = jobOperator.restart(manifestChanged);
        assertThat(fileChanged.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(fileChanged)).contains(
                "SOURCE_FILE_CHANGED: acctdata.txt differs from the first attempt of this job instance");
        assertThat(count("core.account")).isZero();

        // A new instance reads the corrected inputs.
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");
        JobExecution fresh = run();
        assertThat(fresh.getStatus()).as(failures(fresh)).isEqualTo(BatchStatus.COMPLETED);
    }
}
