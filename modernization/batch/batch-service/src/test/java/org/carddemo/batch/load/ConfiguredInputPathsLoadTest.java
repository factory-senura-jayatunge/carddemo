package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.carddemo.batch.PrivateVarDir;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.ExecutionViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Configured input paths (contract section 4.10, Messages outside the root): an operator may stage
 * the load files and the pending-authorization unload below CARDDEMO_VAR_DIR, here the private
 * folder whose name holds {@link PrivateVarDir#SENTINEL}. dataLoadJob's tasklets are not wrapped
 * like the cycle jobs', so Spring Batch stores the step's exception as it is thrown. A missing or
 * unreadable file there fails the job with a message that names the setting or the file's own
 * name, with no cause attached, and the sentinel is in none of the failure exceptions, both
 * EXIT_MESSAGE columns, the poll answer and the log.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "carddemo.data-load.source-dir=${carddemo.var-dir}/inbound/load",
        "carddemo.batch.pauth.source-file=${carddemo.var-dir}/inbound/pauth/DBPAUTP0.dat"})
class ConfiguredInputPathsLoadTest extends DataLoadTestBase {

    static final String FAILURE = DataLoadException.class.getName() + ": ";
    static final Path UNLOAD = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app",
            "app-authorization-ims-db2-mq", "data", "EBCDIC", "AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat")
            .toAbsolutePath().normalize();

    @Autowired
    OutputRoots roots;

    @Autowired
    ExecutionViews views;

    private final List<Path> locked = new ArrayList<>();

    /** Gives back the owner's read, so no later clean-up or test meets a file of mode 000. */
    @AfterEach
    void restoreModes() throws IOException {
        for (Path file : locked) {
            if (Files.exists(file)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        }
        locked.clear();
    }

    @BeforeEach
    void emptyInbound() throws IOException {
        Path inbound = roots.inbound();
        if (Files.exists(inbound)) {
            try (Stream<Path> paths = Files.walk(inbound)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @Test
    void aMissingSourceDirBelowTheVarDirIsNamedBySetting(CapturedOutput output) throws Exception {
        JobExecution execution = run();

        assertReported(execution, "loadAccounts", "acctdata.txt not found in carddemo.data-load.source-dir", output);
        assertThat(count("core.account")).isZero();
    }

    @Test
    void aMissingUnloadBelowTheVarDirIsNamedBySetting(CapturedOutput output) throws Exception {
        stageSample();

        JobExecution execution = run();

        assertReported(execution, "loadPendingAuthorizations",
                "DBPAUTP0.dat not found (carddemo.batch.pauth.source-file)", output);
    }

    @Test
    void anUnreadableLoadFileIsReportedByTheCauseTypeOnly(CapturedOutput output) throws Exception {
        unreadable(stageSample().resolve("acctdata.txt"));

        JobExecution execution = run();

        assertReported(execution, "loadAccounts", "acctdata.txt cannot be read (AccessDeniedException)", output);
    }

    @Test
    void anUnreadableUnloadIsReportedByTheCauseTypeOnly(CapturedOutput output) throws Exception {
        stageSample();
        Path unload = Files.createDirectories(roots.inbound().resolve("pauth")).resolve("DBPAUTP0.dat");
        Files.copy(UNLOAD, unload);
        unreadable(unload);

        JobExecution execution = run();

        assertReported(execution, "loadPendingAuthorizations", "DBPAUTP0.dat cannot be read (AccessDeniedException)",
                output);
    }

    /** {@code <inbound>/load} holding a copy of the sample files. */
    private Path stageSample() throws IOException {
        return sampleWith(Files.createDirectories(roots.inbound().resolve("load")), "acctdata.txt", lines -> lines);
    }

    private void unreadable(Path file) throws IOException {
        locked.add(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        // A superuser reads any file, so the read failure cannot be made there. Any other owner
        // must get it: the test fails rather than skips when an owner that is not uid 0 can read.
        assumeFalse((Integer) Files.getAttribute(file, "unix:uid") == 0, "the test runs as uid 0");
        assertThat(Files.isReadable(file)).as("a file of mode 000 is readable by its owner").isFalse();
    }

    private void assertReported(JobExecution execution, String step, String message, CapturedOutput output) {
        assertThat(roots.varDir().toString()).as("the folder that must stay unnamed").contains(PrivateVarDir.SENTINEL);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions()).as("the failure chain").isNotEmpty()
                .allSatisfy(t -> {
                    assertThat(t).hasToString(FAILURE + message);
                    assertThat(t.getCause()).isNull();
                    assertThat(t.getSuppressed()).isEmpty();
                });
        List<StepExecution> failed = execution.getStepExecutions().stream()
                .filter(s -> s.getStatus() == BatchStatus.FAILED).toList();
        assertThat(failed).extracting(StepExecution::getStepName).containsExactly(step);
        String stepExit = DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_STEP_EXECUTION WHERE STEP_EXECUTION_ID = :id")
                .param("id", failed.getFirst().getId()).query(String.class).single();
        assertStackTraceOf(stepExit, message);

        failureRecords(execution, output).forEach((where, text) -> assertThat(text).as(where).contains(message)
                .doesNotContain(PrivateVarDir.SENTINEL).doesNotContain("Caused by"));
        // It holds statuses and counts, no message.
        assertThat(views.view(execution.getId()).toString()).as("poll answer").doesNotContain(PrivateVarDir.SENTINEL);
    }

    /**
     * The whole stored text: the exception's own line, then only stack frames, the last one
     * possibly cut where the column ends. A frame names a class, a source file name and a line.
     */
    private static void assertStackTraceOf(String exitMessage, String message) {
        List<String> lines = Arrays.asList(exitMessage.split("\\R"));
        assertThat(lines.getFirst()).isEqualTo(FAILURE + message);
        assertThat(lines.subList(1, lines.size())).as("only stack frames follow").isNotEmpty()
                .allMatch(l -> l.startsWith("\tat ") || "\tat ".startsWith(l) || l.matches("\t\\.\\.\\. \\d+ more"));
    }
}
