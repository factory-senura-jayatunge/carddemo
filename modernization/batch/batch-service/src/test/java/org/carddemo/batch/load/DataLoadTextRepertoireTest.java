package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.carddemo.batch.TextFixtures;
import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.RefRows;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The text repertoire's load and export round trip: sample files whose text fields run through
 * the 256 characters a legacy byte maps to. The fields whose columns V411 and V421 widened load
 * and re-export byte for byte; until V414 widens the others, a value they cannot hold fails the load
 * with the record and the column, and nothing of the file is stored.
 */
@BothModes("DEF-ACUP-002")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadTextRepertoireTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();
    /** Read in fixed mode only; outside the source directory, as a harness keeps it. */
    private static final Path MANIFEST = WORK_DIR.resolveSibling(WORK_DIR.getFileName() + ".layout.toml");

    /** The fields of stage 1: their columns hold any legacy value now. */
    private static final Map<String, List<String>> STAGE_1 = Map.of(
            "acctdata.txt", List.of("ACCT-ADDR-ZIP", "ACCT-GROUP-ID", "ACCT-FILLER"),
            "custdata.txt", List.of("CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-GOVT-ISSUED-ID"),
            "carddata.txt", List.of("CARD-FILLER"),
            "tcatbal.txt", List.of("TCATBAL-FILLER"),
            "trantype.txt", List.of("TRAN-TYPE-DESC"),
            "trancatg.txt", List.of("TRAN-CAT-TYPE-DESC"));

    /** Text fields that keep their sample value: keys, which order and link the files, and parsed dates. */
    private static final Set<String> KEYS_AND_DATES = Set.of("ACCT-OPEN-DATE", "ACCT-EXPIRAION-DATE",
            "ACCT-REISSUE-DATE", "CUST-DOB-YYYY-MM-DD", "CARD-NUM", "CARD-EXPIRAION-DATE", "XREF-CARD-NUM",
            "TRANCAT-TYPE-CD", "TRAN-TYPE", "TRAN-TYPE-CD", "DIS-ACCT-GROUP-ID", "DIS-TRAN-TYPE-CD");

    private static final Map<String, RecordLayout> LAYOUTS = Map.of(
            "acctdata.txt", Layouts.ACCOUNT, "custdata.txt", Layouts.CUSTOMER, "carddata.txt", Layouts.CARD,
            "cardxref.txt", Layouts.CARD_XREF, "tcatbal.txt", Layouts.TCAT_BAL, "trantype.txt", Layouts.TRAN_TYPE,
            "trancatg.txt", Layouts.TRAN_CATEGORY, "discgrp.txt", Layouts.DISC_GROUP);

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
        registry.add("carddemo.data-load.account-layout-manifest", MANIFEST::toString);
    }

    private static Path workDir() {
        try {
            return Files.createTempDirectory("carddemo-data-load-repertoire");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void createWorkDir() throws IOException {
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

    /**
     * In fixed mode the manifest declares every record ACUP_UPDATE, whose group ID position must be
     * blank (D-11); the ZIP position's value moves to group_id.
     */
    private static List<String> applicable(String file, List<String> fields) {
        return fields.stream()
                .filter(f -> !(f.equals("ACCT-GROUP-ID") && mode() == FidelityMode.FIXED)).toList();
    }

    /** Copies the sample files, with the named fields of the named files filled from {@code text}. */
    private static void writeFixture(Map<String, List<String>> fields, TextFixtures text) throws IOException {
        for (String name : SAMPLE_FILES) {
            Path target = WORK_DIR.resolve(name);
            List<String> change = fields.get(name);
            if (change == null) {
                Files.copy(SAMPLE_DIR.resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(SAMPLE_DIR.resolve(name), StandardCharsets.ISO_8859_1)) {
                lines.add(text.fill(line, LAYOUTS.get(name), applicable(name, change)));
            }
            Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        }
        if (mode() == FidelityMode.FIXED) {
            writeLayoutManifest(MANIFEST, WORK_DIR.resolve("acctdata.txt"), "ACUP_UPDATE");
        }
    }

    private static List<SourceLine> fixture(String file) {
        return LegacyFileReader.read(WORK_DIR.resolve(file), LAYOUTS.get(file).length());
    }

    private static <T> void assertReExports(String file, List<T> rows, Function<T, byte[]> export) {
        List<SourceLine> source = fixture(file);
        assertThat(rows).as(file).hasSameSizeAs(source);
        List<Integer> different = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (!Arrays.equals(source.get(i).record(), export.apply(rows.get(i)))) {
                different.add(source.get(i).lineNumber());
            }
        }
        assertThat(different).as(file + " lines that do not re-export identically").isEmpty();
    }

    /** Each stored value against the field it came from, without the field's trailing pad. */
    private static void assertStored(String file, String table, String key, Map<String, String> columnToField) {
        List<SourceLine> source = fixture(file);
        RecordLayout layout = LAYOUTS.get(file);
        String select = "SELECT " + String.join(", ", columnToField.keySet()) + " FROM " + table + " ORDER BY " + key;
        List<Map<String, Object>> rows = DB.admin().sql(select).query().listOfRows();
        assertThat(rows).hasSameSizeAs(source);
        for (int i = 0; i < rows.size(); i++) {
            FixedRecord r = source.get(i).view(layout);
            for (Map.Entry<String, String> e : columnToField.entrySet()) {
                String expected = e.getValue() == null ? "" : r.text(e.getValue());
                String stored = (String) rows.get(i).get(e.getKey());
                assertThat(stripPad(stored)).as(file + " line " + source.get(i).lineNumber() + " " + e.getKey())
                        .isEqualTo(expected);
            }
        }
    }

    /** NCHAR columns come back padded with U+0020, as the field is. */
    private static String stripPad(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    @Test
    void theStage1FieldsLoadUnchangedAndReExportByteForByte() throws Exception {
        TextFixtures text = new TextFixtures();
        writeFixture(STAGE_1, text);
        assertThat(text.charactersUsed()).as("every character but LF").isEqualTo(255);

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        CoreRows rows = new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
        assertReExports("acctdata.txt", rows.accounts(), a -> a.toBytes());
        assertReExports("custdata.txt", rows.customers(), c -> c.toBytes());
        // batch_svc reads the CVV masked, so only the admin login sees the bytes that were loaded.
        assertReExports("carddata.txt", new CoreRows(DB.admin()).cards(), c -> c.toBytes());
        assertReExports("tcatbal.txt", rows.categoryBalances(), t -> t.toBytes());
        RefRows refRows = new RefRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
        assertReExports("trantype.txt", refRows.transactionTypes(), t -> t.toBytes());
        assertReExports("trancatg.txt", refRows.categories(), c -> c.toBytes());

        Map<String, String> account = new LinkedHashMap<>();
        if (mode() == FidelityMode.LEGACY) {
            account.put("addr_zip", "ACCT-ADDR-ZIP");
            account.put("group_id", "ACCT-GROUP-ID");
        } else {
            account.put("addr_zip", null);
            account.put("group_id", "ACCT-ADDR-ZIP");
        }
        account.put("legacy_filler", "ACCT-FILLER");
        assertStored("acctdata.txt", "core.account", "acct_id", account);
        List<SourceLine> accounts = fixture("acctdata.txt");
        List<Map<String, Object>> provenance = DB.admin()
                .sql("SELECT legacy_zip_group, zip_group_origin FROM core.account ORDER BY acct_id").query().listOfRows();
        assertThat(provenance).hasSameSizeAs(accounts);
        for (int i = 0; i < accounts.size(); i++) {
            FixedRecord r = accounts.get(i).view(Layouts.ACCOUNT);
            assertThat(provenance.get(i)).as("acctdata.txt line " + accounts.get(i).lineNumber())
                    .containsEntry("legacy_zip_group", r.raw("ACCT-ADDR-ZIP") + r.raw("ACCT-GROUP-ID"))
                    .containsEntry("zip_group_origin", mode() == FidelityMode.LEGACY ? "R" : "M");
        }
        Map<String, String> customer = new LinkedHashMap<>();
        customer.put("addr_line_1", "CUST-ADDR-LINE-1");
        customer.put("addr_line_2", "CUST-ADDR-LINE-2");
        customer.put("govt_issued_id", "CUST-GOVT-ISSUED-ID");
        assertStored("custdata.txt", "core.customer", "cust_id", customer);
        assertStored("carddata.txt", "core.card", "card_num", Map.of("legacy_filler", "CARD-FILLER"));
        assertStored("tcatbal.txt", "core.tcat_bal", "acct_id, type_cd, cat_cd",
                Map.of("legacy_filler", "TCATBAL-FILLER"));
        assertStored("trantype.txt", "ref.tran_type", "type_cd", Map.of("description", "TRAN-TYPE-DESC"));
        assertStored("trancatg.txt", "ref.tran_category", "type_cd, cat_cd",
                Map.of("description", "TRAN-CAT-TYPE-DESC"));
    }

    /** Every text field of the layout but its keys and parsed dates. */
    private static List<String> everyTextField(String file) {
        return LAYOUTS.get(file).fields().stream()
                .filter(f -> f.type() == FieldType.TEXT || (f.type() == FieldType.FILLER && !f.name().startsWith("FILLER-")))
                .map(FieldSpec::name).filter(f -> !KEYS_AND_DATES.contains(f)).toList();
    }

    /** The personal identifiers of each file's records, which no failure may show (D-NFR-04). */
    private static final Map<String, List<String>> IDENTIFIERS = Map.of(
            "acctdata.txt", List.of("ACCT-ID"), "custdata.txt", List.of("CUST-ID", "CUST-SSN"),
            "carddata.txt", List.of("CARD-NUM", "CARD-ACCT-ID"));

    /**
     * Pending V414: the other text columns are still too narrow. Each file whose every text field
     * runs through the repertoire fails its step, naming the file, the line and the column but
     * neither the value nor the record's key, and leaves the table empty rather than holding a
     * cut value or '?'. When V414 lands, this becomes the round trip above for every field.
     */
    @Tag("pending-V414")
    @Test
    void untilV414AValueAStage2ColumnCannotHoldFailsTheLoadNamingTheColumn(CapturedOutput output) throws Exception {
        // trantype.txt and trancatg.txt have no stage 2 field: V421 widened their only text column.
        Map<String, String> tables = Map.of("acctdata.txt", "core.account", "custdata.txt", "core.customer",
                "carddata.txt", "core.card");
        for (Map.Entry<String, String> e : tables.entrySet()) {
            emptyTables();
            writeFixture(Map.of(e.getKey(), everyTextField(e.getKey())), new TextFixtures());

            JobExecution execution = run();

            String failure = failures(execution);
            assertThat(execution.getStatus()).as(e.getKey()).isEqualTo(BatchStatus.FAILED);
            Matcher m = Pattern.compile(Pattern.quote(e.getKey()) + " line (\\d+): " + Pattern.quote(e.getValue())
                    + "\\.[a-z_0-9]+ cannot hold the value: ").matcher(failure);
            assertThat(m.find()).as(e.getKey() + ": " + failure).isTrue();
            assertThat(failure.chars().filter(c -> c > 0x7E || c < 0x20)).as("no value in the message").isEmpty();
            List<String> fields = IDENTIFIERS.get(e.getKey());
            if (fields != null) {
                int lineNumber = Integer.parseInt(m.group(1));
                FixedRecord failed = fixture(e.getKey()).stream().filter(l -> l.lineNumber() == lineNumber)
                        .findFirst().orElseThrow().view(LAYOUTS.get(e.getKey()));
                assertReportedWithout(execution, output, m.group(),
                        fields.stream().map(failed::raw).toList());
            }
            assertThat(count(e.getValue())).as(e.getValue()).isZero();
        }
    }
}
