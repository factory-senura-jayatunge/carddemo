package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.BothModes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * dataLoadJob with the unset FILLER of the update layouts (DEF-ACUP-020, Q-ACUP-010 part (b)),
 * in both modes of DEF-ACUP-002: customer 333-500 is accepted whatever it holds, counted by file
 * line and exported as spaces; account 123-300 is kept as stored, U+0000 included; any other
 * unnamed FILLER still fails its step.
 */
@BothModes("DEF-ACUP-002")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadFillerTest extends DataLoadTestBase {

    private static final String RESIDUE_TEXT = "Residue text left by a save";

    private static final Path WORK_DIR = workDir();

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
    }

    private static Path workDir() {
        try {
            // Outside the repository, as the other load tests' copies.
            return Files.createTempDirectory("carddemo-data-load-filler");
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
        if (!Files.exists(WORK_DIR)) {
            return;
        }
        try (Stream<Path> files = Files.walk(WORK_DIR)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String put(String line, int length, int position, String text) {
        String padded = line + " ".repeat(length - line.length());
        return padded.substring(0, position - 1) + text + padded.substring(position - 1 + text.length());
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow();
    }

    private static List<SourceLine> fixture(String file, int length) {
        return LegacyFileReader.read(WORK_DIR.resolve(file), length);
    }

    private static CoreRows rows() {
        return new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
    }

    @Test
    void theSampleCountsNoCustomer() throws Exception {
        sampleWith(WORK_DIR, "custdata.txt", lines -> lines);

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        var context = step(execution, "loadCustomers").getExecutionContext();
        assertThat(context.getLong(CoreTableLoaders.Customers.FILLER_NORMALISED)).isZero();
        assertThat(context.getString(CoreTableLoaders.Customers.FILLER_NORMALISED_LINES)).isEmpty();
    }

    /** C4 under O2: an all-U+0000 and a text 333-500 load, are counted 2 by line, and export 168 spaces. */
    @Test
    void customerResidueLoadsIsCountedByLineAndExportsSpaces(CapturedOutput output) throws Exception {
        List<String> ids = new ArrayList<>();
        sampleWith(WORK_DIR, "custdata.txt", lines -> {
            List<String> changed = new ArrayList<>(lines);
            changed.set(1, put(changed.get(1), 500, 333, "\0".repeat(168)));
            changed.set(4, put(changed.get(4), 500, 333, RESIDUE_TEXT));
            ids.add(changed.get(1).substring(0, 9));
            ids.add(changed.get(4).substring(0, 9));
            return changed;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        var context = step(execution, "loadCustomers").getExecutionContext();
        assertThat(context.getLong(CoreTableLoaders.Customers.FILLER_NORMALISED)).isEqualTo(2);
        assertThat(context.getString(CoreTableLoaders.Customers.FILLER_NORMALISED_LINES)).isEqualTo("2, 5");
        List<SourceLine> source = fixture("custdata.txt", 500);
        List<CustomerRecord> customers = rows().customers();
        assertThat(customers).hasSameSizeAs(source);
        for (int i = 0; i < source.size(); i++) {
            byte[] export = customers.get(i).toBytes();
            byte[] expected = source.get(i).record().clone();
            Arrays.fill(expected, 332, 500, (byte) ' ');
            assertThat(export).as("custdata.txt line %d", i + 1).isEqualTo(expected);
        }
        String log = output.getAll();
        assertThat(log).contains("loadCustomers: customer FILLER normalised: 2 (positions 333-500 not all spaces,"
                + " exported as spaces): custdata.txt lines 2, 5");
        for (String id : ids) {
            assertThat(log).doesNotContain(id);
            assertThat(context.toString()).doesNotContain(id);
        }
        assertThat(log).doesNotContain(RESIDUE_TEXT).doesNotContain("\0");
    }

    @Test
    void aNulInTheAccountFillerIsKeptAndReExported() throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            List<String> changed = new ArrayList<>(lines);
            changed.set(3, put(changed.get(3), 300, 123, "\0".repeat(178)));
            return changed;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        List<SourceLine> source = fixture("acctdata.txt", 300);
        List<AccountRecord> accounts = rows().accounts();
        assertThat(accounts.get(3).legacyFiller()).isEqualTo("\0".repeat(178));
        for (int i = 0; i < source.size(); i++) {
            assertThat(accounts.get(i).toBytes()).as("acctdata.txt line %d", i + 1).isEqualTo(source.get(i).record());
        }
    }

    @Test
    void anotherUnnamedFillerStillFailsItsStep() throws Exception {
        sampleWith(WORK_DIR, "cardxref.txt", lines -> {
            List<String> changed = new ArrayList<>(lines);
            changed.set(3, put(changed.get(3), 50, 37, "X"));
            return changed;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution)).contains("cardxref.txt line 4: FILLER at position 37 does not hold its constant");
        assertThat(count("core.card_xref")).isZero();
    }
}
