package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A fixed run's publication failures name no account and no path (D-10; contract section 4.8,
 * Logs): a statement file is {@code <acct_id>.pdf}, so its name is personal data. Each case makes
 * step 1, the folder publication or step 3 fail with PUBLICATION_MISMATCH, then checks the exit
 * description, the job's and steps' EXIT_MESSAGE in the job repository, and the log statementJob
 * wrote: none holds an account ID, any run of 11 digits, a {@code .pdf} name or the instance root's
 * path. UUIDs and SHA-256 values are masked before the digit check, since their hex can hold 11
 * digits in a row.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=fixed"})
class StatementFixedPrivacyTest extends StatementJobTestBase {

    private static final Consumer<JobExecution> NOTHING = execution -> { };
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
            + "-[0-9a-fA-F]{12}");
    private static final Pattern SHA256 = Pattern.compile("\\b[0-9a-f]{64}\\b");
    /** A made-up account ID: no sample account has it. */
    private static final String SENTINEL_ACCOUNT = "98765432109";

    @Autowired
    StatementPublication publication;

    @AfterEach
    void noHooks() {
        publication.beforeIntent = NOTHING;
        publication.beforePublish = NOTHING;
        publication.beforeComplete = NOTHING;
    }

    private static Path firstPdf(Path folder) {
        return folder.resolve(names(folder).stream().filter(n -> n.endsWith(".pdf")).findFirst().orElseThrow());
    }

    /** statementJob launched with the log it writes from here on. */
    private record Failed(JobExecution execution, String log) {
    }

    private Failed launchFailing(long cycle, CapturedOutput output) {
        int from = output.getAll().length();
        JobExecution execution = launch("statementJob", cycle);
        return new Failed(execution, output.getAll().substring(from));
    }

    private void assertMismatchNamesNothing(Failed failed, long cycle) throws IOException {
        JobExecution execution = failed.execution();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).startsWith("PUBLICATION_MISMATCH: ");
        List<String> texts = new ArrayList<>();
        texts.add(execution.getExitStatus().getExitDescription());
        texts.addAll(DB.admin().sql("SELECT EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", execution.getId()).query(String.class).list());
        texts.addAll(DB.admin().sql("SELECT EXIT_MESSAGE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", execution.getId()).query(String.class).list());
        texts.add(failed.log());
        List<String> forbidden = new ArrayList<>(DB.admin().sql(
                "SELECT acct_id FROM batch.statement_capture WHERE statement_run_id = :r")
                .param("r", runId(cycle)).query(String.class).list());
        forbidden.add(SENTINEL_ACCOUNT);
        forbidden.add(".pdf");
        forbidden.add(root().toString());
        forbidden.add(root().toRealPath().toString());
        for (String text : texts) {
            String value = String.valueOf(text);
            for (String f : forbidden) {
                assertThat(value).as("forbidden text").doesNotContain(f);
            }
            String masked = SHA256.matcher(UUID.matcher(value).replaceAll("<uuid>")).replaceAll("<sha256>");
            assertThat(masked).doesNotContainPattern("\\d{11}");
        }
    }

    /** Step 1: a working statement file that no longer has the SHA-256 its account row holds. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aChangedWorkingStatementFailsTheIntentNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        publication.beforeIntent = execution -> flipFirstBit(firstPdf(workFolder(cycle, execution.getId())));

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "RUNNING");
    }

    /** Step 1: a statement file of an account the run does not have, in the working folder. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void anOtherAccountsFileInTheWorkingFolderFailsTheIntentNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        publication.beforeIntent = execution -> {
            try {
                Files.write(workFolder(cycle, execution.getId()).resolve(SENTINEL_ACCOUNT + ".pdf"),
                        "not a statement".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(failed.execution().getExitStatus().getExitDescription()).contains("missing 0, other 1");
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "RUNNING");
    }

    /** Step 2: a committed working statement file removed before the folder publication. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aRemovedWorkingStatementFailsThePublicationNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        publication.beforePublish = execution -> {
            try {
                Files.delete(firstPdf(workFolder(cycle, execution.getId())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING");
    }

    /** Step 2: a final folder made in advance whose claim names another output. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void anotherOutputsClaimFailsThePublicationNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        Path target = finalFolder(cycle);
        Files.createDirectories(StatementFolders.statements(root()));
        Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path claim = Files.writeString(target.resolve(FolderPublication.CLAIM), "0".repeat(64) + "  "
                + SENTINEL_ACCOUNT + ".pdf\n");
        Files.setPosixFilePermissions(claim, PosixFilePermissions.fromString("rw-------"));

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING");
        assertThat(names(target)).containsExactly(FolderPublication.CLAIM);
    }

    /** Step 3: a published statement file changed before the final check. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aChangedFinalStatementFailsTheCheckNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        publication.beforeComplete = execution -> flipFirstBit(firstPdf(finalFolder(cycle)));

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING");
    }

    /** Step 3: a published statement file removed before the final check. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aRemovedFinalStatementFailsTheCheckNamingNoAccount(FidelityMode mode, CapturedOutput output)
            throws IOException {
        long cycle = postedSampleCycle();
        publication.beforeComplete = execution -> {
            try {
                Files.delete(firstPdf(finalFolder(cycle)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        Failed failed = launchFailing(cycle, output);

        assertMismatchNamesNothing(failed, cycle);
        assertThat(failed.execution().getExitStatus().getExitDescription()).contains("missing 1, other 0");
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING");
    }
}
