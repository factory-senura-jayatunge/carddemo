package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DEF-ACUP-002 (D-11, ruling v4) without a supplied manifest: where the account load puts the
 * group ID that every sample account holds at the ZIP position. Legacy stores every record as
 * read; fixed moves it to group_id for the records the built-in manifest declares, which covers
 * the ASCII sample by its hash only. Either way the table keeps 103-122 as read and re-exports the
 * file byte for byte, with no mode, until a row's ZIP or group changes.
 */
@BothModes("DEF-ACUP-002")
@SpecItem("DEF-ACUP-002")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class DataLoadGroupIdTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();
    /** ACCT-GROUP-ID, positions 113-122 of the 300-byte record. */
    private static final int GROUP_FROM = 112;
    private static final int GROUP_TO = 122;
    private static final String SAMPLE_SHA256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260";

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
                SELECT addr_zip, RTRIM(group_id) AS group_id, zip_group_origin, COUNT(*) AS n
                FROM core.account GROUP BY addr_zip, group_id, zip_group_origin
                """).query().listOfRows();
    }

    private static CoreRows rows() {
        return new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
    }

    private static List<Integer> linesThatReExportDifferently(Path file) {
        List<SourceLine> source = LegacyFileReader.read(file, Layouts.ACCOUNT.length());
        List<AccountRecord> exported = rows().accounts();
        assertThat(exported).hasSameSizeAs(source);
        return IntStream.range(0, source.size())
                .filter(i -> !Arrays.equals(source.get(i).record(), exported.get(i).toBytes()))
                .mapToObj(i -> source.get(i).lineNumber()).toList();
    }

    private static ExecutionContext loadAccounts(JobExecution execution) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("loadAccounts"))
                .findFirst().orElseThrow().getExecutionContext();
    }

    @Test
    void theGroupIdGoesWhereTheModeSaysAndTheLoadReportsIt() throws Exception {
        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        ExecutionContext report = loadAccounts(execution);
        assertThat(report.getString("acctdataSha256")).isEqualTo(SAMPLE_SHA256);
        assertThat(execution.getExecutionContext().getString("dataLoad.acctdataSha256")).isEqualTo(SAMPLE_SHA256);
        assertThat(execution.getExecutionContext().containsKey("dataLoad.accountLayoutManifestSha256")).isFalse();
        assertThat(report.getString("accountLayoutManifestSha256")).isEmpty();
        assertThat(report.getLong("accountLayoutWarnings")).isZero();
        assertThat(report.getString("groupIdMode")).isEqualTo(mode().key());
        if (mode() == FidelityMode.LEGACY) {
            assertThat(placements()).containsExactly(
                    Map.of("addr_zip", "A000000000", "group_id", "", "zip_group_origin", "R", "n", 50));
            assertThat(report.getLong("accountsAsRead")).isEqualTo(50);
            assertThat(report.getLong("accountsMigrated")).isZero();
            assertThat(report.getString("accountLayoutSource")).isEqualTo("ignored");
        } else {
            assertThat(placements()).containsExactly(
                    Map.of("addr_zip", "", "group_id", "A000000000", "zip_group_origin", "M", "n", 50));
            assertThat(report.getLong("accountsAsRead")).isZero();
            assertThat(report.getLong("accountsMigrated")).isEqualTo(50);
            assertThat(report.getString("accountLayoutSource")).isEqualTo("builtin");
        }
    }

    @Test
    void theAccountsReExportByteForByteWhateverTheModeAndRebuildOnlyWhatChanged() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Path file = WORK_DIR.resolve("acctdata.txt");
        assertThat(linesThatReExportDifferently(file)).isEmpty();
        assertThat(rows().accountExports()).allMatch(CoreRows.AccountExport::restored);

        // X6: the balance and the credit limit change; they show at 13-24 and 25-36 and 103-122 stays as read.
        Map<String, Object> before = DB.admin().sql("SELECT curr_bal, credit_limit FROM core.account"
                + " WHERE acct_id = '00000000002'").query().singleRow();
        DB.admin().sql("UPDATE core.account SET curr_bal = 1234.56, credit_limit = 9876.54"
                + " WHERE acct_id = '00000000002'").update();
        assertThat(linesThatReExportDifferently(file)).containsExactly(2);
        byte[] sourceLine2 = LegacyFileReader.read(file, Layouts.ACCOUNT.length()).get(1).record();
        byte[] exportedLine2 = rows().accounts().get(1).toBytes();
        // S9(10)V99 zoned, sign overpunched on the last digit: 6 positive is F, 4 positive is D.
        assertThat(new String(exportedLine2, 12, 12, StandardCharsets.ISO_8859_1)).isEqualTo("00000012345F");
        assertThat(new String(exportedLine2, 24, 12, StandardCharsets.ISO_8859_1)).isEqualTo("00000098765D");
        assertThat(Arrays.copyOfRange(exportedLine2, 102, 122)).isEqualTo(Arrays.copyOfRange(sourceLine2, 102, 122));
        assertThat(Arrays.copyOfRange(exportedLine2, 0, 12)).isEqualTo(Arrays.copyOfRange(sourceLine2, 0, 12));
        assertThat(Arrays.copyOfRange(exportedLine2, 36, 300)).isEqualTo(Arrays.copyOfRange(sourceLine2, 36, 300));
        assertThat(rows().accountExports().get(1).restored()).isTrue();
        DB.admin().sql("UPDATE core.account SET curr_bal = :b, credit_limit = :c WHERE acct_id = '00000000002'")
                .param("b", before.get("curr_bal")).param("c", before.get("credit_limit")).update();
        assertThat(linesThatReExportDifferently(file)).isEmpty();

        // A changed group is written in the CVACT01Y layout: ZIP at 103, group at 113.
        String zip = mode() == FidelityMode.LEGACY ? "A000000000" : "";
        DB.admin().sql("UPDATE core.account SET group_id = 'GOLD' WHERE acct_id = '00000000002'").update();
        assertThat(linesThatReExportDifferently(file)).containsExactly(2);
        CoreRows.AccountExport changed = rows().accountExports().get(1);
        assertThat(changed.restored()).isFalse();
        assertThat(changed.record().addrZip()).isEqualTo(zip);
        assertThat(changed.record().groupId()).isEqualTo("GOLD");

        // X5: changed back, the load-time text is written again.
        DB.admin().sql("UPDATE core.account SET group_id = :g WHERE acct_id = '00000000002'")
                .param("g", mode() == FidelityMode.LEGACY ? "" : "A000000000").update();
        assertThat(linesThatReExportDifferently(file)).isEmpty();

        // A row without provenance is rebuilt from its columns.
        DB.admin().sql("UPDATE core.account SET legacy_zip_group = NULL, zip_group_origin = NULL"
                + " WHERE acct_id = '00000000003'").update();
        CoreRows.AccountExport none = rows().accountExports().get(2);
        assertThat(none.origin()).isNull();
        assertThat(none.restored()).isFalse();
        assertThat(linesThatReExportDifferently(file))
                .as("legacy rebuilds the same bytes; fixed writes the group at 113").isEqualTo(
                        mode() == FidelityMode.LEGACY ? List.of() : List.of(3));
    }

    @Test
    void aChangedFileIsStoredAsReadInBothModesAndFixedModeWarnsByLine() throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(2);
            lines.set(2, l.substring(0, GROUP_FROM) + "B000000000" + l.substring(GROUP_TO));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(DB.admin().sql("SELECT addr_zip, group_id, zip_group_origin FROM core.account"
                + " WHERE acct_id = '00000000003'").query().singleRow())
                .isEqualTo(Map.of("addr_zip", "A000000000", "group_id", "B000000000", "zip_group_origin", "R"));
        assertThat(placements()).contains(
                Map.of("addr_zip", "A000000000", "group_id", "", "zip_group_origin", "R", "n", 49));
        assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"))).isEmpty();
        ExecutionContext report = loadAccounts(execution);
        assertThat(report.getLong("accountsAsRead")).isEqualTo(50);
        assertThat(report.getLong("accountsMigrated")).isZero();
        if (mode() == FidelityMode.LEGACY) {
            assertThat(report.getString("accountLayoutSource")).isEqualTo("ignored");
            assertThat(report.getLong("accountLayoutWarnings")).isZero();
        } else {
            // Not the sample's bytes: no built-in manifest applies, and the 49 records that look
            // like the update layout are reported, never guessed.
            assertThat(report.getString("accountLayoutSource")).isEqualTo("none");
            assertThat(report.getLong("accountLayoutWarnings")).isEqualTo(49);
        }
    }
    /** E2: one byte changed outside 103-122, no manifest: every record as read, all 50 in the warning. */
    @Test
    void aFileChangedOutsideTheZipAndGroupIsStoredAsReadAndWarnsForEveryLine(CapturedOutput output) throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(0);
            // The last byte of ACCT-FILLER (position 300): not a field the classification reads.
            lines.set(0, l.substring(0, 299) + "X");
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(placements()).containsExactly(
                Map.of("addr_zip", "A000000000", "group_id", "", "zip_group_origin", "R", "n", 50));
        assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"))).isEmpty();
        ExecutionContext report = loadAccounts(execution);
        assertThat(report.getLong("accountsAsRead")).isEqualTo(50);
        if (mode() == FidelityMode.LEGACY) {
            assertThat(report.getString("accountLayoutSource")).isEqualTo("ignored");
            assertThat(report.getLong("accountLayoutWarnings")).isZero();
        } else {
            assertThat(report.getString("accountLayoutSource")).isEqualTo("none");
            assertThat(report.getLong("accountLayoutWarnings")).isEqualTo(50);
            assertThat(output.getAll()).contains("loadAccounts: 50 records stored as read have a blank 113-122 and a"
                    + " non-blank 103-112: acctdata.txt lines 1-50");
            assertThat(output.getAll()).doesNotContain("00000000001");
        }
    }

    /** E16: undeclared, 113 holds spaces and NULs: data, stored as read, and not in the warning. */
    @Test
    void aMixedGroupFieldIsDataAndNotWarned(CapturedOutput output) throws Exception {
        String mixed = "     " + "\0".repeat(5);
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(2);
            lines.set(2, l.substring(0, GROUP_FROM) + mixed + l.substring(GROUP_TO));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        String group = DB.admin().sql("SELECT group_id FROM core.account WHERE acct_id = '00000000003'")
                .query((rs, n) -> rs.getString(1)).single();
        assertThat(group.codePoints().toArray()).containsExactly(mixed.codePoints().toArray());
        assertThat(DB.admin().sql("SELECT zip_group_origin FROM core.account WHERE acct_id = '00000000003'")
                .query(String.class).single()).isEqualTo("R");
        assertThat(linesThatReExportDifferently(WORK_DIR.resolve("acctdata.txt"))).isEmpty();
        ExecutionContext report = loadAccounts(execution);
        if (mode() == FidelityMode.LEGACY) {
            assertThat(report.getLong("accountLayoutWarnings")).isZero();
        } else {
            assertThat(report.getLong("accountLayoutWarnings")).isEqualTo(49);
            assertThat(output.getAll()).contains("loadAccounts: 49 records stored as read have a blank 113-122 and a"
                    + " non-blank 103-112: acctdata.txt lines 1-2, 4-50");
        }
    }
}
