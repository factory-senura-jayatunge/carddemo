package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The checks of the load, on copies of the sample files that each test changes. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadValidationTest extends DataLoadTestBase {

    private static final Path WORK_DIR = workDir();

    @DynamicPropertySource
    static void workData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", WORK_DIR::toString);
    }

    private static Path workDir() {
        try {
            // Outside the repository: copies of the sample files under it would make
            // the spec checker's file citations ambiguous.
            return Files.createTempDirectory("carddemo-data-load-validation");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void removeWorkDir() throws IOException {
        try (Stream<Path> files = Files.walk(WORK_DIR)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void duplicateKeyFailsTheStepAndLeavesTheTableEmpty(CapturedOutput output) throws Exception {
        String account = Files.readAllLines(SAMPLE_DIR.resolve("acctdata.txt")).get(0).substring(0, 11);
        sampleWith(WORK_DIR, "acctdata.txt", lines -> {
            lines.add(1, lines.get(0));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertReportedWithout(execution, output, "acctdata.txt line 2: duplicate key, the same as line 1",
                List.of(account));
        assertThat(failedStep(execution)).isEqualTo("loadAccounts");
        assertThat(count("core.account")).isZero();
    }

    @Test
    void outOfOrderCardNumbersAreReportedByTheirLinesOnly(CapturedOutput output) throws Exception {
        List<String> original = Files.readAllLines(SAMPLE_DIR.resolve("carddata.txt"));
        String first = original.get(0).substring(0, 16);
        String second = original.get(1).substring(0, 16);
        sampleWith(WORK_DIR, "carddata.txt", lines -> {
            String l = lines.remove(0);
            lines.add(1, l);
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadCards");
        assertReportedWithout(execution, output, "carddata.txt line 2: key out of order, below that of line 1",
                List.of(first, second, original.get(0).substring(16, 27), original.get(1).substring(16, 27)));
        assertThat(failures(execution)).as("not even masked").doesNotContain(first.substring(12))
                .doesNotContain(second.substring(12));
        assertThat(count("core.card")).isZero();
        assertThat(count("core.account")).isEqualTo(50);
    }

    @Test
    void crossReferenceToAnAccountThatIsNotLoadedFails(CapturedOutput output) throws Exception {
        String line = Files.readAllLines(SAMPLE_DIR.resolve("cardxref.txt")).get(3);
        sampleWith(WORK_DIR, "cardxref.txt", lines -> {
            String l = lines.get(3);
            lines.set(3, l.substring(0, 25) + "99999999999" + l.substring(36));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadCardXref");
        assertReportedWithout(execution, output, "cardxref.txt line 4: XREF-ACCT-ID names an account that is not loaded",
                List.of("99999999999", line.substring(0, 16), line.substring(16, 25)));
        assertThat(count("core.card_xref")).isZero();
    }

    @Test
    void crossReferenceToACustomerThatIsNotLoadedFails(CapturedOutput output) throws Exception {
        String line = Files.readAllLines(SAMPLE_DIR.resolve("cardxref.txt")).get(3);
        sampleWith(WORK_DIR, "cardxref.txt", lines -> {
            String l = lines.get(3);
            lines.set(3, l.substring(0, 16) + "999999999" + l.substring(25));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadCardXref");
        assertReportedWithout(execution, output, "cardxref.txt line 4: XREF-CUST-ID names a customer that is not loaded",
                List.of("999999999", line.substring(0, 16), line.substring(25, 36)));
        assertThat(count("core.card_xref")).isZero();
    }

    @Test
    void aCategoryBalanceOfAnAccountThatIsNotLoadedFails(CapturedOutput output) throws Exception {
        int last = Files.readAllLines(SAMPLE_DIR.resolve("tcatbal.txt")).size();
        sampleWith(WORK_DIR, "tcatbal.txt", lines -> {
            lines.set(last - 1, "99999999999" + lines.get(last - 1).substring(11));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadCategoryBalances");
        assertReportedWithout(execution, output,
                "tcatbal.txt line " + last + ": TRANCAT-ACCT-ID names an account that is not loaded",
                List.of("99999999999"));
        assertThat(count("core.tcat_bal")).isZero();
    }

    @Test
    void aCategoryOfATypeThatIsNotLoadedFails() throws Exception {
        sampleWith(WORK_DIR, "trancatg.txt", lines -> {
            lines.add("09" + lines.getLast().substring(2));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadCategories");
        assertThat(failures(execution))
                .contains("trancatg.txt line 19: TRAN-TYPE-CD names a transaction type that is not loaded");
        assertThat(count("ref.tran_category")).isZero();
        assertThat(count("ref.tran_type")).isEqualTo(7);
    }
    @Test
    void disclosureGroupsWithoutTheDefaultGroupFail() throws Exception {
        sampleWith(WORK_DIR, "discgrp.txt", lines -> {
            lines.removeIf(l -> l.startsWith("DEFAULT   "));
            return lines;
        });

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedStep(execution)).isEqualTo("loadDisclosureGroups");
        assertThat(failures(execution)).contains("discgrp.txt: no DEFAULT group");
        assertThat(count("ref.disclosure_group")).isZero();
    }

    @Test
    void missingSourceFileFailsBeforeAnyRowIsWritten() throws Exception {
        sampleWith(WORK_DIR, "acctdata.txt", lines -> lines);
        Files.delete(WORK_DIR.resolve("acctdata.txt"));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution)).contains("acctdata.txt not found");
        assertThat(count("core.account")).isZero();
    }

    private static String failedStep(JobExecution execution) {
        return execution.getStepExecutions().stream().filter(s -> s.getStatus() == BatchStatus.FAILED)
                .map(StepExecution::getStepName).findFirst().orElse(null);
    }
}
