package org.carddemo.verify.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * verify-data's re-export check for customer positions 333-500 (DEF-ACUP-020, Q-ACUP-010 part
 * (b)), without a database: it counts the source lines whose 333-500 is not all spaces on its
 * own, compares 1-332 exactly and expects 168 spaces at 333-500. Account 123-300 is compared as
 * stored, U+0000 included.
 */
class CustomerFillerReconciliationTest {

    private static final Path SAMPLE = Path.of(System.getProperty("carddemo.repo.root", "../../.."))
            .toAbsolutePath().normalize().resolve("app/data/ASCII");
    private static final String RESIDUE_TEXT = "Residue text left by a save";

    @TempDir
    Path dir;

    private static String put(String line, int length, int position, String text) {
        String padded = line + " ".repeat(length - line.length());
        return padded.substring(0, position - 1) + text + padded.substring(position - 1 + text.length());
    }

    private List<SourceLine> write(LoadedTable t, List<String> lines) throws IOException {
        Path file = dir.resolve(t.file());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        return LegacyFileReader.read(file, t.layout().length());
    }

    private static List<String> sample(String file) throws IOException {
        return Files.readAllLines(SAMPLE.resolve(file), StandardCharsets.ISO_8859_1);
    }

    /** Lines 2 and 5: all U+0000 and text at 333-500. */
    private static List<String> customersWithResidue() throws IOException {
        List<String> lines = new ArrayList<>(sample("custdata.txt"));
        lines.set(1, put(lines.get(1), 500, 333, "\0".repeat(168)));
        lines.set(4, put(lines.get(4), 500, 333, RESIDUE_TEXT));
        return lines;
    }

    /** Reconciles the table against the source, as if the table held exactly what {@code export} gives per line. */
    private static Report reconcile(LoadedTable t, List<SourceLine> source, Function<byte[], byte[]> export) {
        List<String> keys = new ArrayList<>();
        List<byte[]> exported = new ArrayList<>();
        for (SourceLine l : source) {
            keys.add(new String(l.record(), 0, t.keyLength(), StandardCharsets.ISO_8859_1));
            exported.add(export.apply(l.record()));
        }
        Map<String, BigDecimal> totals = new HashMap<>();
        for (String field : t.sums().keySet()) {
            totals.put(field, source.stream().map(l -> l.view(t.layout()).decimal(field))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        Report report = new Report();
        DataReconciliation.compare(report, t, source, keys.size(), keys, totals, exported);
        return report;
    }

    private static Report.Line line(Report report, String subject) {
        return report.lines().stream().filter(l -> l.subject().equals(subject)).findFirst().orElseThrow();
    }

    private static String printed(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        report.print(new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSampleCountsZeroAndReExportsByteForByte() {
        LoadedTable t = LoadedTable.named("customer");
        List<SourceLine> source = LegacyFileReader.read(SAMPLE.resolve(t.file()), t.layout().length());

        Report report = reconcile(t, source, r -> CustomerRecord.parse(r).toBytes());

        assertThat(source).hasSize(50);
        assertThat(line(report, "core.customer").outcome()).isEqualTo(Report.Outcome.OK);
        assertThat(line(report, "core.customer FILLER").detail()).isEqualTo("customer FILLER normalised: 0");
    }

    @Test
    void residueAt333To500IsCountedByLineAndComparedAsSpaces() throws IOException {
        LoadedTable t = LoadedTable.named("customer");
        List<String> lines = customersWithResidue();
        List<SourceLine> source = write(t, lines);

        Report report = reconcile(t, source, r -> CustomerRecord.parse(r).toBytes());

        assertThat(report.failures()).isEmpty();
        assertThat(line(report, "core.customer FILLER").detail()).isEqualTo("customer FILLER normalised: 2"
                + " (positions 333-500 not all spaces, compared as spaces): custdata.txt lines 2, 5");
        String output = printed(report);
        for (int i : new int[] {1, 4}) {
            assertThat(output).as("customer ID of line %d", i + 1).doesNotContain(lines.get(i).substring(0, 9));
        }
        assertThat(output).doesNotContain(RESIDUE_TEXT).doesNotContain("\0");
    }

    @Test
    void anExportThatKeepsTheResidueFails() throws IOException {
        LoadedTable t = LoadedTable.named("customer");
        List<SourceLine> source = write(t, customersWithResidue());

        Report report = reconcile(t, source, r -> r.clone());

        assertThat(line(report, "core.customer").detail()).isEqualTo("2 records re-export differently: custdata.txt"
                + " line 2 [FILLER at 333], line 5 [FILLER at 333]");
        assertThat(line(report, "core.customer FILLER").detail()).startsWith("customer FILLER normalised: 2 ");
    }

    @Test
    void positions1To332AreComparedExactlyAndANulThereIsData() throws IOException {
        LoadedTable t = LoadedTable.named("customer");
        List<String> lines = customersWithResidue();
        // CUST-MIDDLE-NAME, 35-59: a NUL there is a value, not residue.
        lines.set(2, put(lines.get(2), 500, 35, "\0".repeat(25)));
        List<SourceLine> source = write(t, lines);

        Report kept = reconcile(t, source, r -> CustomerRecord.parse(r).toBytes());
        Report blanked = reconcile(t, source, r -> {
            byte[] export = CustomerRecord.parse(r).toBytes();
            for (int i = 34; i < 59; i++) {
                if (export[i] == 0) {
                    export[i] = ' ';
                }
            }
            return export;
        });

        assertThat(kept.failures()).isEmpty();
        assertThat(line(kept, "core.customer FILLER").detail()).startsWith("customer FILLER normalised: 2 ");
        assertThat(line(blanked, "core.customer").detail())
                .isEqualTo("1 records re-export differently: custdata.txt line 3 [CUST-MIDDLE-NAME]");
    }

    @Test
    void theAccountFiller123To300IsComparedAsStored() throws IOException {
        LoadedTable t = LoadedTable.named("account");
        List<String> lines = new ArrayList<>(sample("acctdata.txt"));
        lines.set(3, put(lines.get(3), 300, 123, "\0".repeat(178)));
        List<SourceLine> source = write(t, lines);

        Report kept = reconcile(t, source, r -> AccountRecord.parse(r).toBytes());
        Report blanked = reconcile(t, source, r -> {
            byte[] export = AccountRecord.parse(r).toBytes();
            java.util.Arrays.fill(export, 122, 300, (byte) ' ');
            return export;
        });

        assertThat(kept.failures()).isEmpty();
        assertThat(kept.lines()).noneMatch(l -> l.subject().equals("core.account FILLER"));
        assertThat(line(blanked, "core.account").detail())
                .isEqualTo("1 records re-export differently: acctdata.txt line 4 [ACCT-FILLER]");
    }

    @Test
    void onlyTheCustomerTableDropsPositions() {
        for (LoadedTable t : LoadedTable.ALL) {
            assertThat(t.spaces()).as(t.name())
                    .isEqualTo(t.name().equals("customer") ? new LoadedTable.Spaces(333, 500) : null);
        }
    }

    @Test
    void lineRangesAreCompactAndBounded() {
        assertThat(DataReconciliation.ranges(List.of(3, 7, 8, 9, 12))).isEqualTo("3, 7-9, 12");
        List<Integer> alternate = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            alternate.add(2 * i);
        }
        assertThat(DataReconciliation.ranges(alternate)).startsWith("2, 4, 6").endsWith(", 200 and 150 more");
    }
}
