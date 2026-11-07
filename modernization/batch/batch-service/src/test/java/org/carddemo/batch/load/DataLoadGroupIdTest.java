package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DEF-ACUP-002 (D-11): where the account load puts the group ID that every sample account
 * holds at the ZIP position. Legacy keeps it there for INTCALC's legacy comparison; fixed
 * moves it to group_id. Either way the table re-exports the file byte for byte.
 */
@BothModes("DEF-ACUP-002")
@SpecItem("DEF-ACUP-002")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadGroupIdTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();
    /** ACCT-GROUP-ID, positions 113-122 of the 300-byte record. */
    private static final int GROUP_FROM = 112;
    private static final int GROUP_TO = 122;

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
    }

    private static Path workDir() {
        try {
            // Outside the repository, as in DataLoadValidationTest.
            return Files.createTempDirectory("carddemo-data-load-group-id");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void unchangedSample() throws IOException {
        // The class runs once per mode; @AfterAll of the first run removes the directory.
        Files.createDirectories(WORK_DIR);
        sampleWith(WORK_DIR, "acctdata.txt", lines -> lines);
    }

    @AfterAll
    static void removeWorkDir() throws IOException {
        if (!Files.exists(WORK_DIR)) {
            return;
        }
        try (Stream<Path> files = Files.walk(WORK_DIR)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static FidelityMode mode() {
        return FidelityOverrides.current();
    }

    private static List<Map<String, Object>> placements() {
        return DB.admin().sql("""
                SELECT addr_zip, RTRIM(group_id) AS group_id, COUNT(*) AS n
                FROM core.account GROUP BY addr_zip, group_id
                """).query().listOfRows();
    }

    private static List<Integer> linesThatReExportDifferently(Path file, FidelityMode readMode) {
        CoreRows rows = new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)), readMode);
        List<SourceLine> source = LegacyFileReader.read(file, Layouts.ACCOUNT.length());
        List<AccountRecord> exported = rows.accounts();
        assertThat(exported).hasSameSizeAs(source);
        return IntStream.range(0, source.size())
                .filter(i -> !Arrays.equals(source.get(i).record(), exported.get(i).toBytes()))
                .mapToObj(i -> source.get(i).lineNumber()).toList();
    }

    @Test
    void theGroupIdGoesWhereTheModeSays() throws Exception {
        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        if (mode() == FidelityMode.LEGACY) {
            assertThat(placements()).containsExactly(Map.of("addr_zip", "A000000000", "group_id", "", "n", 50));
        } else {
            assertThat(placements()).containsExactly(Map.of("addr_zip", "", "group_id", "A000000000", "n", 50));
        }
    }

    @Test
    void theAccountsReExportByteForByteInTheModeTheyWereLoadedIn() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"), mode())).isEmpty();
        FidelityMode other = mode() == FidelityMode.LEGACY ? FidelityMode.FIXED : FidelityMode.LEGACY;
        assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"), other))
                .as("read in the other mode, every account differs").hasSize(50);
    }

    @Test
    void aGroupIdAtItsOwnPositionIsKeptInLegacyAndRefusedInFixed() throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(2);
            lines.set(2, l.substring(0, GROUP_FROM) + "B000000000" + l.substring(GROUP_TO));
            return lines;
        });

        JobExecution execution = run();

        if (mode() == FidelityMode.LEGACY) {
            assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
            assertThat(DB.admin().sql("SELECT addr_zip, group_id FROM core.account WHERE acct_id = '00000000003'")
                    .query().singleRow()).isEqualTo(Map.of("addr_zip", "A000000000", "group_id", "B000000000"));
            assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"), mode())).isEmpty();
        } else {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failures(execution)).contains("acctdata.txt line 3").contains("ACCT-GROUP-ID is not blank");
            assertThat(count("core.account")).isZero();
        }
    }
}
