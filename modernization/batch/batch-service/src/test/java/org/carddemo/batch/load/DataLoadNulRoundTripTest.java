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
import java.util.stream.Stream;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
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
 * DEF-ACUP-002 ruling v4, the required NUL round trip: account records whose 103-122 holds
 * U+0000 go through the real loader in both modes, and group_id, addr_zip and legacy_zip_group
 * read back through JDBC hold the code points and length of table 1.6 (cases E7, E8, E14, E15);
 * the export gives back the input bytes (X7). The supplied manifest declares every record
 * ACUP_UPDATE; legacy mode does not read it.
 */
@BothModes("DEF-ACUP-002")
@SpecItem("DEF-ACUP-002")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadNulRoundTripTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();
    private static final Path MANIFEST = WORK_DIR.resolveSibling(WORK_DIR.getFileName() + ".layout.toml");
    /** ACCT-ADDR-ZIP and ACCT-GROUP-ID, positions 103-122 of the 300-byte record (0-based 102-121). */
    private static final int ZIP_FROM = 102;
    private static final int GROUP_TO = 122;
    private static final String NUL10 = "\0".repeat(10);
    private static final String SP10 = " ".repeat(10);
    /** E7: 103 A000000000, 113 ten NULs. */
    private static final String E7 = "A000000000" + NUL10;
    /** E8: 103 ten NULs, 113 spaces. */
    private static final String E8 = NUL10 + SP10;
    /** E14: 103 A0000, U+0000 and four spaces; 113 spaces. */
    private static final String E14 = "A0000\0    " + SP10;
    /** E15: 103 A000000000, 113 five spaces and five NULs: not blank. */
    private static final String E15 = "A000000000" + "     \0\0\0\0\0";

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
        registry.add("carddemo.data-load.account-layout-manifest", MANIFEST::toString);
    }

    private static Path workDir() {
        try {
            return Files.createTempDirectory("carddemo-data-load-nul");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void workDirExists() throws IOException {
        // The class runs once per mode; @AfterAll of the first run removes the directory.
        Files.createDirectories(WORK_DIR);
    }

    @AfterAll
    static void removeWorkDir() throws IOException {
        Files.deleteIfExists(MANIFEST);
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

    private static Path acctdata() {
        return WORK_DIR.resolve("acctdata.txt");
    }

    /** The sample with 103-122 of the given lines (1-based) replaced, and a manifest declaring all ACUP_UPDATE. */
    private static void sampleWithZipGroups(String... byLine) {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            for (int i = 0; i < byLine.length; i++) {
                String l = lines.get(i);
                assertThat(byLine[i]).hasSize(20);
                lines.set(i, l.substring(0, ZIP_FROM) + byLine[i] + l.substring(GROUP_TO));
            }
            return lines;
        });
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");
    }

    /** The three columns of one account as JDBC returns them, and its origin. */
    private record Stored(String addrZip, String groupId, String legacyZipGroup, String origin) {
    }

    private static Stored stored(String acctId) {
        return DB.admin().sql("SELECT addr_zip, group_id, legacy_zip_group, zip_group_origin FROM core.account"
                + " WHERE acct_id = :id").param("id", acctId)
                .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    /** group_id is NCHAR(10): the stored value comes back padded with U+0020 to 10. */
    private static String nchar10(String value) {
        return value + " ".repeat(10 - value.length());
    }

    /** Compares code point by code point, so a NUL and a space or a lost character never look alike. */
    private static void assertSameText(String actual, String expected, String what) {
        assertThat(actual).as(what).isNotNull();
        assertThat(actual.codePoints().toArray()).as(what + " code points").containsExactly(
                expected.codePoints().toArray());
        assertThat(actual).as(what + " length").hasSize(expected.length());
    }

    private static void assertStored(String acctId, String zipGroup, String addrZip, String groupId, String origin) {
        Stored s = stored(acctId);
        assertSameText(s.addrZip(), addrZip, acctId + " addr_zip");
        assertSameText(s.groupId(), nchar10(groupId), acctId + " group_id");
        assertSameText(s.legacyZipGroup(), zipGroup, acctId + " legacy_zip_group");
        assertThat(s.origin()).as(acctId + " zip_group_origin").isEqualTo(origin);
    }

    private static CoreRows rows() {
        return new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
    }

    @Test
    void nulsInTheZipAndGroupPositionsRoundTripThroughTheLoaderAndTheExport() throws Exception {
        sampleWithZipGroups(E7, E8, E14);

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        if (mode() == FidelityMode.LEGACY) {
            // As read: both fields without trailing U+0020, NULs kept.
            assertStored("00000000001", E7, "A000000000", NUL10, "R");
            assertStored("00000000002", E8, NUL10, "", "R");
            assertStored("00000000003", E14, "A0000\0", "", "R");
        } else {
            // ACUP_UPDATE: ZIP empty; group '' when 103-112 is blank (ten NULs is blank), else 103-112
            // without trailing U+0020.
            assertStored("00000000001", E7, "", "A000000000", "M");
            assertStored("00000000002", E8, "", "", "M");
            assertStored("00000000003", E14, "", "A0000\0", "M");
        }

        // X7: the export writes the input bytes back, NULs included.
        List<SourceLine> source = LegacyFileReader.read(acctdata(), Layouts.ACCOUNT.length());
        List<CoreRows.AccountExport> exported = rows().accountExports();
        assertThat(exported).hasSameSizeAs(source);
        for (int i = 0; i < source.size(); i++) {
            byte[] bytes = exported.get(i).record().toBytes();
            assertThat(bytes).as("line %d", source.get(i).lineNumber()).isEqualTo(source.get(i).record());
            assertThat(exported.get(i).restored()).as("line %d restored", source.get(i).lineNumber()).isTrue();
        }
        assertThat(new String(exported.get(0).record().toBytes(), ZIP_FROM, 20, StandardCharsets.ISO_8859_1))
                .isEqualTo(E7);
    }

    @Test
    void aDeclaredUpdateRecordWithSpacesAndNulsAt113FailsInFixedModeAndIsDataInLegacy() throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            String l = lines.get(3);
            lines.set(3, l.substring(0, ZIP_FROM) + E15 + l.substring(GROUP_TO));
            return lines;
        });
        writeLayoutManifest(MANIFEST, acctdata(), "ACUP_UPDATE");

        JobExecution execution = run();

        if (mode() == FidelityMode.LEGACY) {
            assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
            assertStored("00000000004", E15, "A000000000", "     \0\0\0\0\0", "R");
            byte[] line4 = rows().accountExports().get(3).record().toBytes();
            byte[] source = LegacyFileReader.read(acctdata(), Layouts.ACCOUNT.length()).get(3).record();
            assertThat(line4).isEqualTo(source);
            assertThat(Arrays.copyOfRange(line4, ZIP_FROM, GROUP_TO))
                    .isEqualTo(E15.getBytes(StandardCharsets.ISO_8859_1));
        } else {
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failures(execution))
                    .contains("acctdata.txt line 4: declared ACUP_UPDATE but positions 113-122 are not blank");
            // clearCoreTables runs first, so "unchanged" is empty.
            assertThat(count("core.account")).isZero();
        }
    }
}
