package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The FILLER rules of the load steps, without a database (DEF-ACUP-020, Q-ACUP-010 part (b)):
 * customer 333-500 may hold anything and is counted by file line; every other unnamed FILLER
 * must hold its constant; named fields and named FILLERs keep U+0000 as data.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoadFillerRulesTest {

    // Not DataLoadTestBase.SAMPLE_DIR: that class opens the test database.
    private static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data",
            "ASCII").toAbsolutePath().normalize();
    private static final String RESIDUE_TEXT = "Residue text left by a save";

    @TempDir
    Path dir;

    /** Each loader as dataLoadJob builds it; the checks below need neither the database nor the switches. */
    private static final Map<String, TableLoadTasklet> LOADERS = Map.of(
            "acctdata.txt", new CoreTableLoaders.Accounts(null, null, null, null),
            "custdata.txt", new CoreTableLoaders.Customers(null, null, null),
            "carddata.txt", new CoreTableLoaders.Cards(null, null, null),
            "cardxref.txt", new CoreTableLoaders.CardXrefs(null, null, null),
            "tcatbal.txt", new CoreTableLoaders.CategoryBalances(null, null, null),
            "trantype.txt", new RefTableLoaders.TransactionTypes(null, null, null),
            "trancatg.txt", new RefTableLoaders.Categories(null, null, null),
            "discgrp.txt", new RefTableLoaders.DisclosureGroups(null, null, null));

    private static List<String> sample(String file) throws IOException {
        return Files.readAllLines(SAMPLE_DIR.resolve(file), StandardCharsets.ISO_8859_1);
    }

    /** The line padded to the record, with {@code text} at 1-based {@code position}. */
    private static String put(String line, RecordLayout layout, int position, String text) {
        String padded = line + " ".repeat(layout.length() - line.length());
        return padded.substring(0, position - 1) + text + padded.substring(position - 1 + text.length());
    }

    private SourceFile write(String file, List<String> lines) throws IOException {
        Files.writeString(dir.resolve(file), String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        return SourceFile.read(dir, file, LOADERS.get(file).layout());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Customer lines 2, 5 and 6 changed at 333-500: all U+0000, text, one byte at 500. */
    private static List<String> customersWithResidue() throws IOException {
        List<String> lines = new ArrayList<>(sample("custdata.txt"));
        lines.set(1, put(lines.get(1), Layouts.CUSTOMER, 333, "\0".repeat(168)));
        lines.set(4, put(lines.get(4), Layouts.CUSTOMER, 333, RESIDUE_TEXT));
        lines.set(5, put(lines.get(5), Layouts.CUSTOMER, 500, "x"));
        return lines;
    }

    @Test
    void theCustomerStepDropsPositions333To500AndNoOtherStepDropsAnything() {
        LOADERS.forEach((file, loader) -> assertThat(loader.droppedFillers()).as(file)
                .isEqualTo(file.equals("custdata.txt") ? Set.of(333) : Set.of()));
        FieldSpec filler = Layouts.CUSTOMER.fields().stream()
                .filter(f -> f.position() == CoreTableLoaders.Customers.FILLER_POSITION).findFirst().orElseThrow();
        assertThat(filler.name()).startsWith("FILLER-");
        assertThat(filler.length()).isEqualTo(168);
        assertThat(filler.end()).isEqualTo(500);
        assertThat(filler.fill()).isEqualTo(' ');
    }

    @Test
    void everySampleFilePassesAndNoSampleCustomerIsCounted() {
        LOADERS.forEach((file, loader) -> {
            SourceFile source = SourceFile.read(SAMPLE_DIR, file, loader.layout());
            assertThat(source.requireRepresentable(loader.droppedFillers())).as(file).isEmpty();
        });
        assertThat(SourceFile.read(SAMPLE_DIR, "custdata.txt", Layouts.CUSTOMER).lines()).hasSize(50);
    }

    @Test
    void customerResidueAt333To500IsAcceptedAndCountedByLine() throws IOException {
        SourceFile file = write("custdata.txt", customersWithResidue());

        assertThat(file.requireRepresentable(Set.of(CoreTableLoaders.Customers.FILLER_POSITION)))
                .containsExactly(2, 5, 6);
    }

    @Test
    void withoutTheExceptionTheSameCustomerFileFailsAsBefore() throws IOException {
        SourceFile file = write("custdata.txt", customersWithResidue());

        assertThatThrownBy(() -> file.requireRepresentable(Set.of()))
                .isInstanceOf(DataLoadException.class)
                .hasMessage("custdata.txt line 2: FILLER at position 333 does not hold its constant");
    }

    @Test
    void aCustomerExportWrites168SpacesAndKeeps1To332() throws IOException {
        for (String line : customersWithResidue()) {
            byte[] source = bytes(line);
            byte[] export = CustomerRecord.parse(source).toBytes();
            assertThat(java.util.Arrays.copyOfRange(export, 0, 332)).isEqualTo(java.util.Arrays.copyOfRange(source, 0, 332));
            assertThat(new String(export, 332, 168, StandardCharsets.ISO_8859_1)).isEqualTo(" ".repeat(168));
        }
    }

    @Test
    void aNulInANamedCustomerFieldIsDataNotResidue() throws IOException {
        List<String> lines = new ArrayList<>(sample("custdata.txt"));
        // CUST-MIDDLE-NAME is 35-59; a save writes a field received as low-values as low-values.
        lines.set(2, put(lines.get(2), Layouts.CUSTOMER, 35, "\0".repeat(25)));
        SourceFile file = write("custdata.txt", lines);

        assertThat(file.requireRepresentable(Set.of(333))).isEmpty();
        byte[] source = file.lines().get(2).record();
        CustomerRecord c = CustomerRecord.parse(source);
        assertThat(c.middleName()).isEqualTo("\0".repeat(25));
        assertThat(c.toBytes()).isEqualTo(source);
    }

    @Test
    void aNulInTheAccountFiller123To300IsKeptAndReExported() throws IOException {
        List<String> lines = new ArrayList<>(sample("acctdata.txt"));
        lines.set(3, put(lines.get(3), Layouts.ACCOUNT, 123, "\0".repeat(178)));
        lines.set(6, put(lines.get(6), Layouts.ACCOUNT, 200, "\0 \0"));
        SourceFile file = write("acctdata.txt", lines);

        assertThat(file.requireRepresentable(LOADERS.get("acctdata.txt").droppedFillers())).isEmpty();
        for (int i : new int[] {3, 6}) {
            byte[] source = file.lines().get(i).record();
            AccountRecord a = AccountRecord.parse(source);
            assertThat(a.legacyFiller()).contains("\0");
            assertThat(a.toBytes()).isEqualTo(source);
        }
    }

    /** Every other unnamed FILLER keeps failing closed, naming the file line and the position only. */
    @ParameterizedTest
    @CsvSource({"cardxref.txt, 37, X", "discgrp.txt, 23, 1", "trantype.txt, 53, 1", "trancatg.txt, 57, 1",
            "cardxref.txt, 50, NUL", "discgrp.txt, 50, SPACE", "trantype.txt, 60, NUL"})
    void everyOtherUnnamedFillerMustHoldItsConstant(String file, int position, String value) throws IOException {
        TableLoadTasklet loader = LOADERS.get(file);
        List<String> lines = new ArrayList<>(sample(file));
        String text = switch (value) {
            case "NUL" -> "\0";
            case "SPACE" -> " ";
            default -> value;
        };
        lines.set(3, put(lines.get(3), loader.layout(), position, text));
        SourceFile source = write(file, lines);
        int fillerPosition = loader.layout().fields().stream()
                .filter(f -> f.name().startsWith("FILLER-") && f.offset() < position && position <= f.end())
                .findFirst().orElseThrow().position();

        assertThatThrownBy(() -> source.requireRepresentable(loader.droppedFillers()))
                .isInstanceOf(DataLoadException.class)
                .hasMessage(file + " line 4: FILLER at position " + fillerPosition + " does not hold its constant");
    }

    @Test
    void onlyAnUnnamedFillerCanBeDropped() throws IOException {
        SourceFile xref = SourceFile.read(SAMPLE_DIR, "cardxref.txt", Layouts.CARD_XREF);
        SourceFile accounts = SourceFile.read(SAMPLE_DIR, "acctdata.txt", Layouts.ACCOUNT);

        assertThatThrownBy(() -> xref.requireRepresentable(Set.of(333))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.requireRepresentable(Set.of(123))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStepReportsTheCountAndLinesWithoutIdsOrContent(CapturedOutput output) throws IOException {
        List<String> lines = customersWithResidue();
        SourceFile file = write("custdata.txt", lines);
        CoreTableLoaders.Customers customers = (CoreTableLoaders.Customers) LOADERS.get("custdata.txt");
        StepContribution contribution = contribution();

        customers.reportDroppedFillers(contribution, file, file.requireRepresentable(customers.droppedFillers()));

        var context = contribution.getStepExecution().getExecutionContext();
        assertThat(context.getLong(CoreTableLoaders.Customers.FILLER_NORMALISED)).isEqualTo(3);
        assertThat(context.getString(CoreTableLoaders.Customers.FILLER_NORMALISED_LINES)).isEqualTo("2, 5-6");
        assertThat(output.getAll()).contains("loadCustomers: customer FILLER normalised: 3 (positions 333-500 not all"
                + " spaces, exported as spaces): custdata.txt lines 2, 5-6");
        for (int i : new int[] {1, 4, 5}) {
            String id = lines.get(i).substring(0, 9);
            assertThat(output.getAll()).as("customer ID of line %d", i + 1).doesNotContain(id);
            assertThat(context.toString()).as("customer ID of line %d", i + 1).doesNotContain(id);
        }
        assertThat(output.getAll()).doesNotContain(RESIDUE_TEXT).doesNotContain("\0");
    }

    @Test
    void theSampleIsReportedAsZero(CapturedOutput output) {
        SourceFile file = SourceFile.read(SAMPLE_DIR, "custdata.txt", Layouts.CUSTOMER);
        CoreTableLoaders.Customers customers = (CoreTableLoaders.Customers) LOADERS.get("custdata.txt");
        StepContribution contribution = contribution();

        customers.reportDroppedFillers(contribution, file, file.requireRepresentable(customers.droppedFillers()));

        var context = contribution.getStepExecution().getExecutionContext();
        assertThat(context.getLong(CoreTableLoaders.Customers.FILLER_NORMALISED)).isZero();
        assertThat(context.getString(CoreTableLoaders.Customers.FILLER_NORMALISED_LINES)).isEmpty();
        assertThat(output.getAll()).contains("loadCustomers: customer FILLER normalised: 0");
    }

    @Test
    void lineRangesAreCompactAndBounded() {
        assertThat(CoreTableLoaders.Customers.ranges(List.of())).isEmpty();
        assertThat(CoreTableLoaders.Customers.ranges(List.of(7))).isEqualTo("7");
        assertThat(CoreTableLoaders.Customers.ranges(List.of(3, 7, 8, 9, 12))).isEqualTo("3, 7-9, 12");
        List<Integer> alternate = IntStream.rangeClosed(1, 250).map(i -> 2 * i).boxed().toList();
        String shown = CoreTableLoaders.Customers.ranges(alternate);
        assertThat(shown).startsWith("2, 4, 6").endsWith(", 200 and 150 more");
    }

    private static StepContribution contribution() {
        JobExecution job = new JobExecution(1L, new JobInstance(1L, "dataLoadJob"), new JobParameters());
        return new StepExecution(1L, "loadCustomers", job).createStepContribution();
    }
}
