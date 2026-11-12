package org.carddemo.verify.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.carddemo.batch.data.AccountLayouts;
import org.carddemo.batch.data.ZipGroup;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.UserSecurityRecord;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The findings of verify-data, without a database: what the tables would hold is built from the
 * sample files, with one key missing, one key added and one record changed. Every finding must
 * point at the record (file line, or row ordinal) and the printed report must hold none of the
 * keys of the file nor the added one, masked card numbers included.
 */
class ReconciliationFindingsTest {

    private static final Path ROOT = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    private static final Path SAMPLE = ROOT.resolve("app/data/ASCII");
    private static final Path USERS = ROOT.resolve("app/jcl/DUSRSECJ.jcl");

    /** The missing record and the changed one, as lines of the sample file. */
    private static final int MISSING_LINE = 3;
    private static final int CHANGED_LINE = 7;

    private static List<SourceLine> source(LoadedTable t) {
        return LegacyFileReader.read(SAMPLE.resolve(t.file()), t.layout().length());
    }

    private static String key(LoadedTable t, byte[] record) {
        return new String(record, 0, t.keyLength(), StandardCharsets.ISO_8859_1);
    }

    /** A key of the table's length that no line of the file has. */
    private static String absentKey(LoadedTable t, List<SourceLine> source) {
        String k = "9".repeat(t.keyLength());
        assertThat(source).noneMatch(l -> key(t, l.record()).equals(k));
        return k;
    }

    /** The first field after the key, the one the changed record differs in. */
    private static FieldSpec changedField(LoadedTable t) {
        return t.layout().fields().stream().filter(f -> f.offset() <= t.keyLength() && t.keyLength() < f.end())
                .findFirst().orElseThrow();
    }

    private static String printed(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.print(new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String detail(Report report, String subject) {
        return report.lines().stream().filter(l -> l.subject().equals(subject)).map(Report.Line::detail)
                .findFirst().orElseThrow();
    }

    /** Reconciles the file against a table that lacks line 3, has an extra key and re-exports line 7 changed. */
    private static Report reconcileWithOneOfEach(LoadedTable t, List<SourceLine> source, String extraKey) {
        List<String> tableKeys = new ArrayList<>();
        List<byte[]> exported = new ArrayList<>();
        int changedAt = changedField(t).offset();
        for (SourceLine l : source) {
            if (l.lineNumber() == MISSING_LINE) {
                continue;
            }
            byte[] record = l.record().clone();
            if (l.lineNumber() == CHANGED_LINE) {
                record[changedAt] = (byte) (record[changedAt] == 'X' ? 'Y' : 'X');
            }
            tableKeys.add(key(t, record));
            exported.add(record);
        }
        tableKeys.add(extraKey);
        Map<String, BigDecimal> totals = new HashMap<>();
        for (String field : t.sums().keySet()) {
            totals.put(field, source.stream().map(l -> l.view(t.layout()).decimal(field))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        Report report = new Report();
        DataReconciliation.compare(report, t, source, tableKeys.size(), tableKeys, totals, exported);
        return report;
    }

    private static void assertNoKeyOf(LoadedTable t, List<SourceLine> source, String extraKey, String output) {
        assertThat(output).doesNotContain(extraKey);
        for (SourceLine l : source) {
            String k = key(t, l.record());
            assertThat(output).as("%s line %d's key", t.file(), l.lineNumber()).doesNotContain(k.strip());
            if (t.keyLength() == 16) {
                assertThat(output).as("%s line %d's masked key", t.file(), l.lineNumber())
                        .doesNotContain(Masking.lastFour(k));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "customer", "card", "card_xref", "tcat_bal"})
    void aMissingAnExtraAndAChangedRecordArePointedAtWithoutTheirKeys(String name) {
        LoadedTable t = LoadedTable.named(name);
        List<SourceLine> source = source(t);
        String extraKey = absentKey(t, source);

        Report report = reconcileWithOneOfEach(t, source, extraKey);

        assertThat(detail(report, t.table())).isEqualTo("missing keys at " + t.file() + " line " + MISSING_LINE
                + "; 1 keys not in the file: " + t.table() + " row " + source.size() + " in key order"
                + "; 1 records re-export differently: " + t.file() + " line " + CHANGED_LINE
                + " [" + changedField(t).name() + "]");
        String output = printed(report);
        assertThat(output).startsWith("fail  " + t.table() + " ").contains(t.file() + " line " + MISSING_LINE);
        assertNoKeyOf(t, source, extraKey, output);
    }

    @Test
    void manyMissingRecordsAreCountedAndTheFirstLinesShown() {
        LoadedTable t = LoadedTable.named("account");
        List<SourceLine> source = source(t);
        List<String> tableKeys = source.stream().skip(12).map(l -> key(t, l.record())).toList();
        List<byte[]> exported = source.stream().skip(12).map(SourceLine::record).toList();
        Map<String, BigDecimal> totals = new HashMap<>();
        t.sums().keySet().forEach(f -> totals.put(f, BigDecimal.ZERO));
        Report report = new Report();

        DataReconciliation.compare(report, t, source, tableKeys.size(), tableKeys, totals, exported);

        assertThat(detail(report, "core.account")).startsWith("count " + (source.size() - 12) + ", file " + source.size()
                + "; missing keys at acctdata.txt lines 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 and 2 more; total ");
        assertNoKeyOf(t, source, absentKey(t, source), printed(report));
    }

    @Test
    void aReferenceCodeKeyIsStillNamed() {
        LoadedTable t = LoadedTable.named("disclosure_group");
        List<SourceLine> source = source(t);
        String changed = key(t, source.get(CHANGED_LINE - 1).record());
        String missing = key(t, source.get(MISSING_LINE - 1).record());

        Report report = reconcileWithOneOfEach(t, source, "ZZZZZZZZZZ" + "999999");

        assertThat(detail(report, t.table())).contains("missing keys " + missing)
                .contains("keys not in the file ZZZZZZZZZZ999999")
                .contains("1 records re-export differently: " + changed + " [");
    }

    @Test
    void relationViolationsArePointedAtByFileLineOrCountedWithoutTheirKeys() {
        LoadedTable card = LoadedTable.named("card");
        List<SourceLine> cards = source(card);
        LoadedTable tcat = LoadedTable.named("tcat_bal");
        List<SourceLine> balances = source(tcat);
        String unknownCard = absentKey(card, cards);
        Report report = new Report();

        DataReconciliation.relation(report, "card has xref", card, cards,
                List.of(key(card, cards.get(4).record()), unknownCard, key(card, cards.get(1).record())));
        DataReconciliation.relation(report, "tcat_bal account exists", tcat, balances,
                List.of(key(tcat, balances.get(9).record())));
        DataReconciliation.relation(report, "xref account exists", LoadedTable.named("card_xref"), List.of(), List.of());

        assertThat(detail(report, "card has xref"))
                .isEqualTo("3 violations: carddata.txt lines 2, 5; 1 row of core.card not in carddata.txt");
        assertThat(detail(report, "tcat_bal account exists")).isEqualTo("1 violations: tcatbal.txt line 10");
        assertThat(detail(report, "xref account exists")).isEqualTo("no violations");
        String output = printed(report);
        assertNoKeyOf(card, cards, unknownCard, output);
        assertNoKeyOf(tcat, balances, unknownCard, output);
        for (SourceLine l : balances) {
            assertThat(output).doesNotContain(new String(l.record(), 0, 11, StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void aUserOutsideTheSeedJobIsOnlyCounted() {
        List<UserSecurityRecord> seed = DataReconciliation.seedUsers(USERS);
        List<UserSecurityRecord> users = new ArrayList<>(seed.subList(1, seed.size()));
        String outsider = "QZ" + "Q".repeat(6);
        users.add(new UserSecurityRecord(outsider, "A", "B", "U"));
        UserSecurityRecord renamed = users.get(2);
        users.set(2, new UserSecurityRecord(renamed.userId(), renamed.firstName(), "OTHER", renamed.userType()));
        Report report = new Report();

        DataReconciliation.users(report, seed, users);

        assertThat(detail(report, "iam.app_user")).isEqualTo("missing users " + seed.getFirst().userId()
                + "; 1 user not in DUSRSECJ; " + renamed.userId() + " [last_name]");
        assertThat(printed(report)).doesNotContain(outsider);
    }

    /** The rows a load in {@code layouts} stores for {@code source}, by account ID. */
    private static Map<String, Map<String, Object>> storedRows(List<SourceLine> source, AccountLayouts layouts) {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (SourceLine l : source) {
            AccountRecord a = AccountRecord.parse(l.record());
            ZipGroup.Stored s = ZipGroup.classify(ZipGroup.asRead(a), layouts.layoutOf(a.acctId()));
            Map<String, Object> row = new HashMap<>();
            row.put("addr_zip", s.addrZip());
            row.put("group_id", s.groupId());
            row.put("legacy_zip_group", s.legacyZipGroup());
            row.put("zip_group_origin", s.origin());
            rows.put(a.acctId(), row);
        }
        return rows;
    }

    private static Set<String> accountIds(List<SourceLine> source) {
        return source.stream().map(l -> new String(l.record(), 0, 11, StandardCharsets.ISO_8859_1))
                .collect(Collectors.toSet());
    }

    @Test
    void check6FindsAChangedColumnByLineAndSkipsAMissingRowWithoutKeys() throws Exception {
        LoadedTable t = LoadedTable.named("account");
        List<SourceLine> source = source(t);
        byte[] bytes = Files.readAllBytes(SAMPLE.resolve(t.file()));
        AccountLayouts layouts = AccountLayouts.resolve(FidelityMode.FIXED, bytes, accountIds(source), null);
        Map<String, Map<String, Object>> rows = storedRows(source, layouts);
        AccountLayoutCheck.Layouts checked = AccountLayoutCheck.resolve(FidelityMode.FIXED, bytes, accountIds(source),
                null);
        rows.remove(key(t, source.get(MISSING_LINE - 1).record()));
        Map<String, Object> changed = rows.get(key(t, source.get(CHANGED_LINE - 1).record()));
        changed.put("group_id", changed.get("group_id") + "X");
        changed.put("zip_group_origin", ZipGroup.AS_READ);
        Report report = new Report();

        DataReconciliation.zipGroups(report, source, checked, rows);

        assertThat(detail(report, DataReconciliation.ZIP_GROUP)).isEqualTo("1 records differ: acctdata.txt line "
                + CHANGED_LINE + " [group_id, zip_group_origin] (as read 0, migrated " + source.size()
                + ", layout manifest builtin)");
        assertNoKeyOf(t, source, absentKey(t, source), printed(report));
    }

    @Test
    void check6AsReadInLegacyModeAndTrailingSpacesOfTheNcharGroupIgnored() throws Exception {
        LoadedTable t = LoadedTable.named("account");
        List<SourceLine> source = source(t);
        byte[] bytes = Files.readAllBytes(SAMPLE.resolve(t.file()));
        AccountLayouts layouts = AccountLayouts.resolve(FidelityMode.LEGACY, bytes, accountIds(source), null);
        Map<String, Map<String, Object>> rows = storedRows(source, layouts);
        AccountLayoutCheck.Layouts checked = AccountLayoutCheck.resolve(FidelityMode.LEGACY, bytes, accountIds(source),
                null);
        rows.values().forEach(r -> r.put("group_id", r.get("group_id") + " ".repeat(10 - ((String) r.get("group_id")).length())));
        Report report = new Report();

        DataReconciliation.zipGroups(report, source, checked, rows);

        assertThat(report.lines()).singleElement().extracting(Report.Line::outcome).isEqualTo(Report.Outcome.OK);
        assertThat(detail(report, DataReconciliation.ZIP_GROUP)).endsWith("(as read " + source.size()
                + ", migrated 0, layout manifest ignored)");
    }

    @Test
    void check6RejectsAnUpdateLayoutRecordWithDataAt113To122() throws Exception {
        LoadedTable t = LoadedTable.named("account");
        List<SourceLine> sample = source(t);
        List<String> lines = new ArrayList<>(Files.readAllLines(SAMPLE.resolve(t.file()), StandardCharsets.ISO_8859_1));
        String line = lines.get(CHANGED_LINE - 1);
        lines.set(CHANGED_LINE - 1, line.substring(0, 112) + "GROUPDATA " + line.substring(122));
        byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        List<SourceLine> source = LegacyFileReader.read(new java.io.ByteArrayInputStream(bytes), t.layout().length(),
                t.file());
        String manifest = "schema = 1\nsha256 = \"" + AccountLayouts.sha256(bytes) + "\"\ndefault_layout = \"ACUP_UPDATE\"\n";
        AccountLayouts layouts = AccountLayouts.resolve(FidelityMode.FIXED, bytes, accountIds(source),
                manifest.getBytes(StandardCharsets.US_ASCII));
        Map<String, Map<String, Object>> rows = storedRows(sample, layouts);
        AccountLayoutCheck.Layouts checked = AccountLayoutCheck.resolve(FidelityMode.FIXED, bytes, accountIds(source),
                manifest.getBytes(StandardCharsets.US_ASCII));
        Report report = new Report();

        DataReconciliation.zipGroups(report, source, checked, rows);

        assertThat(detail(report, DataReconciliation.ZIP_GROUP)).isEqualTo("1 records differ: acctdata.txt line "
                + CHANGED_LINE + " declared ACUP_UPDATE but positions 113-122 are not blank (as read 0, migrated "
                + (source.size() - 1) + ", layout manifest supplied)");
        assertThat(printed(report)).doesNotContain("GROUPDATA");
    }
}
