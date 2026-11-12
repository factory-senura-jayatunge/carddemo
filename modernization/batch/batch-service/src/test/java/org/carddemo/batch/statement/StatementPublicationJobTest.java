package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * statementJob's publication in fixed mode at the points batch.md names ("Runs, restart and
 * rerun"; "Tests (statement job)", publication): a crash after the render and before
 * {@code PUBLISHING}, after {@code PUBLISHING} and before the first link, after some of the links,
 * after the manifest is linked and before {@code COMPLETED}, and after {@code COMPLETED}; a file
 * changed before step 1 or step 3; a final folder made in advance; a removed file; unsafe
 * {@code statements/} folders; and the restart's clean-up of working folders. A crash is the
 * execution stopped at the start of a step through the publication's test hook: its committed
 * state is what a killed process leaves. Each restart must give the same final folder bytes and
 * account rows, and exactly one final folder.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=fixed"})
class StatementPublicationJobTest extends StatementJobTestBase {

    private static final Consumer<JobExecution> NOTHING = execution -> { };
    private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    @Autowired
    StatementPublication publication;

    /** Thrown by a hook: the execution ends there, as a killed process would. */
    static final class Crash extends RuntimeException {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

    @AfterEach
    void noHooks() {
        publication.beforeIntent = NOTHING;
        publication.beforePublish = NOTHING;
        publication.beforeComplete = NOTHING;
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow();
    }

    private static void assertCompletedBy(JobExecution execution, long cycle) {
        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", execution.getId());
    }

    private static void assertFailed(JobExecution execution, String code) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).startsWith(code + ": ");
    }

    private static void createFolder(Path folder) throws IOException {
        Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(FOLDER));
        Files.setPosixFilePermissions(folder, FOLDER);
    }

    // ---- crashes, each followed by a restart ----

    /**
     * The render committed its rows and wrote its folder, then the execution ended before the
     * intent. The restart claims the run, removes the crashed execution's working folder, renders
     * again and publishes: the same files and rows.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterTheRenderAndBeforeTheIntentRendersTheSameFilesAndRowsAgain(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> rendered = new AtomicReference<>();
        AtomicReference<Map<String, Map<String, Object>>> rows = new AtomicReference<>();
        publication.beforeIntent = execution -> {
            rendered.set(hashes(workFolder(cycle, execution.getId())));
            rows.set(accountRows(runId(cycle)));
            throw new Crash();
        };

        JobExecution first = launch("statementJob", cycle);
        publication.beforeIntent = NOTHING;

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runRow(cycle)).containsEntry("status", "RUNNING").containsEntry("job_execution_id", first.getId())
                .containsEntry("work_folder", null);
        assertThat(rows.get()).isNotEmpty();
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertThat(names(workFolder(cycle, first.getId()))).isNotEmpty();

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedBy(restart, cycle);
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(rendered.get()));
        assertThat(accountRows(runId(cycle))).isEqualTo(rows.get());
        assertOneFinalFolder(cycle);
        assertRunMode(cycle, mode);
    }

    /**
     * Recovery set the crashed owner's run FAILED. The restart's clean-up removes that owner's
     * working folder, which is terminal and named by no run row, and nothing else: a folder of
     * another run in {@code .work} stays.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void theRestartOfAFailedRunRemovesOnlyTheTerminalOwnersWorkingFolder(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        publication.beforeIntent = execution -> {
            throw new Crash();
        };
        JobExecution first = launch("statementJob", cycle);
        publication.beforeIntent = NOTHING;
        DB.admin().sql("""
                UPDATE batch.statement_run SET status = 'FAILED', failure_code = 'OWNER_LOST'
                WHERE cycle_id = :c AND status = 'RUNNING'""").param("c", cycle).update();
        Path stale = workFolder(cycle, first.getId());
        assertThat(names(stale)).isNotEmpty();
        Path other = StatementFolders.workRoot(root()).resolve((cycle + 1000) + "-1." + first.getId());
        createFolder(other);

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedBy(restart, cycle);
        assertThat(runRow(cycle)).containsEntry("failure_code", null);
        assertThat(Files.exists(stale, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(names(StatementFolders.workRoot(root()))).containsExactly(other.getFileName().toString());
        assertRunMode(cycle, mode);
    }

    /** The intent committed, then the execution ended before any link: the restart publishes the committed folder. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterTheIntentAndBeforeTheFirstLinkPublishesTheCommittedFolder(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> committed = new AtomicReference<>();
        AtomicReference<Map<String, Map<String, Object>>> rows = new AtomicReference<>();
        publication.beforePublish = execution -> {
            committed.set(hashes(workFolder(cycle, execution.getId())));
            rows.set(accountRows(runId(cycle)));
            throw new Crash();
        };
        JobExecution first = launch("statementJob", cycle);
        publication.beforePublish = NOTHING;
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING")
                .containsEntry("work_folder", cycle + "-1." + first.getId());
        assertThat(Files.exists(finalFolder(cycle))).isFalse();

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedBy(restart, cycle);
        assertThat(step(restart, "render").getWriteCount()).isZero();
        assertThat(step(restart, "render").getReadCount()).isZero();
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(committed.get()));
        assertThat(accountRows(runId(cycle))).isEqualTo(rows.get());
        assertOneFinalFolder(cycle);
        assertRunMode(cycle, mode);
    }

    /**
     * The state a crash leaves after the claim and some of the links (contract section 4.8): the
     * final folder made {@code 0700}, {@code .claim} and the first files linked from the committed
     * working folder, no manifest. The restart links the rest and the manifest; no file that was
     * already linked is replaced.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterSomeLinksIsCompletedWithoutReplacingALinkedFile(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        publication.beforePublish = execution -> {
            throw new Crash();
        };
        JobExecution first = launch("statementJob", cycle);
        publication.beforePublish = NOTHING;
        Path work = workFolder(cycle, first.getId());
        SortedMap<String, String> committed = hashes(work);
        Path target = finalFolder(cycle);
        createFolder(target);
        Files.createLink(target.resolve(FolderPublication.CLAIM), work.resolve(FolderPublication.MANIFEST));
        List<String> files = new ArrayList<>(committed.keySet());
        files.remove(FolderPublication.MANIFEST);
        for (String name : files.subList(0, 10)) {
            Files.createLink(target.resolve(name), work.resolve(name));
        }
        SortedMap<String, FileState> linked = states(target);

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedBy(restart, cycle);
        assertThat(hashes(target)).isEqualTo(published(committed));
        SortedMap<String, FileState> after = states(target);
        linked.forEach((name, state) -> assertThat(after.get(name)).as(name).isEqualTo(state));
        assertOneFinalFolder(cycle);
        assertRunMode(cycle, mode);
    }

    /** The manifest is linked, then the execution ended before COMPLETED: the restart only verifies and completes. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterTheManifestAndBeforeCompletedOnlyVerifies(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, FileState>> linked = new AtomicReference<>();
        publication.beforeComplete = execution -> {
            linked.set(states(finalFolder(cycle)));
            throw new Crash();
        };
        launch("statementJob", cycle);
        publication.beforeComplete = NOTHING;
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING");
        Map<String, Map<String, Object>> rows = accountRows(runId(cycle));

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedBy(restart, cycle);
        assertThat(step(restart, "render").getWriteCount()).isZero();
        assertThat(step(restart, "render").getReadCount()).isZero();
        assertThat(states(finalFolder(cycle))).isEqualTo(linked.get());
        assertThat(accountRows(runId(cycle))).isEqualTo(rows);
        assertOneFinalFolder(cycle);
        assertRunMode(cycle, mode);
    }

    /** COMPLETED committed, then the job ended before its own completion: the restart claims nothing and checks the folder. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aRestartOfACompletedRunOnlyChecksTheFolder(FidelityMode mode) {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        assertCompletedBy(first, cycle);
        SortedMap<String, FileState> published = states(finalFolder(cycle));
        Map<String, Map<String, Object>> rows = accountRows(runId(cycle));
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", first.getId());
        assertThat(restart.getExecutionContext().get(StatementPublication.OUTPUT)).isEqualTo(runId(cycle));
        assertThat(states(finalFolder(cycle))).isEqualTo(published);
        assertThat(accountRows(runId(cycle))).isEqualTo(rows);
        assertRunMode(cycle, mode);
    }

    // ---- files changed, folders made in advance ----

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aWorkingFileChangedBeforeTheIntentFailsAndNothingIsPublished(FidelityMode mode) {
        long cycle = postedSampleCycle();
        publication.beforeIntent = execution -> {
            Path work = workFolder(cycle, execution.getId());
            flipFirstBit(work.resolve(names(work).getFirst()));
        };

        JobExecution execution = launch("statementJob", cycle);

        assertFailed(execution, "PUBLICATION_MISMATCH");
        assertThat(runRow(cycle)).containsEntry("status", "RUNNING").containsEntry("work_folder", null)
                .containsEntry("manifest_sha256", null);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertRunMode(cycle, mode);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aFinalFileChangedBeforeStepThreeFailsAndStaysPublishing(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        AtomicReference<Path> changed = new AtomicReference<>();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        publication.beforeComplete = execution -> {
            Path file = finalFolder(cycle).resolve(names(finalFolder(cycle)).stream()
                    .filter(n -> n.endsWith(".pdf")).findFirst().orElseThrow());
            flipFirstBit(file);
            changed.set(file);
            try {
                bytes.set(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        JobExecution execution = launch("statementJob", cycle);

        assertFailed(execution, "PUBLICATION_MISMATCH");
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING").containsEntry("job_execution_id",
                execution.getId());
        assertThat(Files.readAllBytes(changed.get())).as("nothing is replaced").isEqualTo(bytes.get());
        assertRunMode(cycle, mode);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aFinalFileRemovedAfterThePublicationFailsStepThreeAndStaysPublishing(FidelityMode mode) {
        long cycle = postedSampleCycle();
        publication.beforeComplete = execution -> {
            try {
                Files.delete(finalFolder(cycle).resolve(names(finalFolder(cycle)).stream()
                        .filter(n -> n.endsWith(".pdf")).findFirst().orElseThrow()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        JobExecution execution = launch("statementJob", cycle);

        assertFailed(execution, "PUBLICATION_MISMATCH");
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING");
        assertRunMode(cycle, mode);
    }

    /** A final folder made in advance whose claim has other bytes: no file is linked into it. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aFinalFolderMadeInAdvanceWithOtherBytesFailsBeforeAnyLink(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        Path target = finalFolder(cycle);
        Files.createDirectories(StatementFolders.statements(root()));
        createFolder(target);
        Path claim = Files.writeString(target.resolve(FolderPublication.CLAIM), "not this run's manifest\n");
        Files.setPosixFilePermissions(claim, FILE);
        FileState before = FileState.of(claim);

        JobExecution execution = launch("statementJob", cycle);

        assertFailed(execution, "PUBLICATION_MISMATCH");
        assertThat(runRow(cycle)).containsEntry("status", "PUBLISHING");
        assertThat(names(target)).containsExactly(FolderPublication.CLAIM);
        assertThat(FileState.of(claim)).isEqualTo(before);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ? AND status = 'COMPLETED'",
                cycle)).isZero();
        assertRunMode(cycle, mode);
    }

    // ---- unsafe folders and modes ----

    private Path freshStatementsFolder() throws IOException {
        Path statements = StatementFolders.statements(root());
        if (Files.exists(statements, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(statements)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        return statements;
    }

    /** {@code statements/} made {@code 0755} in advance fails the start; its mode is kept and no run is captured. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aStatementsFolderOpenToOthersFailsTheStartAndKeepsItsMode(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        Path statements = freshStatementsFolder();
        Files.createDirectory(statements);
        Set<PosixFilePermission> open = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(statements, open);

        JobExecution execution = launch("statementJob", cycle);

        assertFailed(execution, "OUTPUT_PERMISSIONS");
        assertThat(Files.getPosixFilePermissions(statements, LinkOption.NOFOLLOW_LINKS)).isEqualTo(open);
        assertThat(names(statements)).isEmpty();
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?", cycle)).isZero();
    }

    /** {@code statements/} as a symbolic link fails the start; the link and its target are left as they were. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aStatementsFolderThatIsALinkFailsTheStart(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        Path statements = freshStatementsFolder();
        Path elsewhere = Files.createTempDirectory("statement-link-target");
        try {
            Files.createSymbolicLink(statements, elsewhere);

            JobExecution execution = launch("statementJob", cycle);

            assertFailed(execution, "OUTPUT_PERMISSIONS");
            assertThat(Files.isSymbolicLink(statements)).isTrue();
            assertThat(Files.readSymbolicLink(statements)).isEqualTo(elsewhere);
            assertThat(names(elsewhere)).isEmpty();
            assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?", cycle)).isZero();
        } finally {
            Files.deleteIfExists(statements);
            Files.deleteIfExists(elsewhere);
        }
    }

    /** Every folder the job leaves is {@code 0700} and every file {@code 0600}. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void everyFolderIsPrivateToTheServiceAndEveryFileToo(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        assertCompletedBy(launch("statementJob", cycle), cycle);

        try (Stream<Path> paths = Files.walk(StatementFolders.statements(root()))) {
            for (Path p : paths.toList()) {
                assertThat(Files.isSymbolicLink(p)).as(p.getFileName().toString()).isFalse();
                assertThat(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS))
                        .as(p.getFileName().toString()).isEqualTo(Files.isDirectory(p) ? FOLDER : FILE);
            }
        }
        assertRunMode(cycle, mode);
    }
}
