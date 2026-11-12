package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 * statementJob's crash and restart cases in legacy mode, at each point where the code branches on
 * the mode (batch.md, "Build rulings", legacy crash cases): a crash after the render and before
 * {@code PUBLISHING}, after {@code PUBLISHING} and before the first link, and after the manifest is
 * linked and before {@code COMPLETED}. A crash is the execution stopped at the start of a step
 * through the publication's test hook, as in {@link StatementPublicationJobTest}. Each restart
 * gives the same final folder as an uninterrupted legacy run, the CBSTM03A harness's two files and
 * their manifest, with no account rows and exactly one final folder. The crash after some links
 * and the lease-loss cases run in fixed mode only: the publication code is the same in both modes
 * ({@link StatementPublicationModesTest}).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=legacy"})
class StatementPublicationLegacyJobTest extends StatementJobTestBase {

    private static final Consumer<JobExecution> NOTHING = execution -> { };
    private static final Properties GOLDEN = StatementGoldens.expected("sample-posttran");

    @Autowired
    StatementPublication publication;

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

    /** COMPLETED by {@code execution}, the harness's files and their manifest only, no account rows, one final folder. */
    private void assertCompletedAsAnUninterruptedRun(JobExecution execution, long cycle) {
        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("mode", "LEGACY").containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", execution.getId())
                .containsEntry("statements", Integer.parseInt(GOLDEN.getProperty("statements")))
                .containsEntry("transactions", 262L).containsEntry("unassigned", 0L).containsEntry("adjusted", 0);
        Path folder = finalFolder(cycle);
        assertThat(names(folder)).containsExactlyInAnyOrder(FolderPublication.CLAIM, FolderPublication.MANIFEST,
                StatementFolders.HTML, StatementFolders.TEXT);
        assertThat(StatementGoldens.difference(GOLDEN, "text", 80, null, read(folder.resolve(StatementFolders.TEXT))))
                .isNull();
        assertThat(StatementGoldens.difference(GOLDEN, "html", 100, null, read(folder.resolve(StatementFolders.HTML))))
                .isNull();
        assertThat(accountRows(runId(cycle))).isEmpty();
        assertOneFinalFolder(cycle);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The render wrote its folder, then the execution ended before the intent. The restart claims
     * the RUNNING run, renders again with the legacy renderer from the capture and the master rows
     * up to {@code W}, and step 1 verifies the legacy file set: the same bytes as the crashed render.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterTheRenderAndBeforePublishingRendersTheSameFilesAgain(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> rendered = new AtomicReference<>();
        publication.beforeIntent = execution -> {
            rendered.set(hashes(workFolder(cycle, execution.getId())));
            throw new StatementPublicationJobTest.Crash();
        };

        JobExecution first = launch("statementJob", cycle);
        publication.beforeIntent = NOTHING;

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "RUNNING")
                .containsEntry("job_execution_id", first.getId()).containsEntry("work_folder", null);
        assertThat(rendered.get().keySet()).containsExactlyInAnyOrder(FolderPublication.MANIFEST, StatementFolders.HTML,
                StatementFolders.TEXT);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        long watermark = ((Number) runRow(cycle).get("watermark_tran_pk")).longValue();

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedAsAnUninterruptedRun(restart, cycle);
        assertThat(step(restart, "render").getWriteCount()).isEqualTo(Integer.parseInt(GOLDEN.getProperty("statements")));
        assertThat(runRow(cycle)).containsEntry("watermark_tran_pk", watermark)
                .containsEntry("work_folder", cycle + "-1." + restart.getId());
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(rendered.get()));
    }

    /**
     * The intent committed, then the execution ended before any link. The restart claims the
     * PUBLISHING run, renders nothing, publishes the committed legacy file set from step 2, and
     * step 3 checks it against {@code manifest_sha256} with no account rows.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterPublishingAndBeforeTheFirstLinkPublishesTheLegacyFiles(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, String>> committed = new AtomicReference<>();
        publication.beforePublish = execution -> {
            committed.set(hashes(workFolder(cycle, execution.getId())));
            throw new StatementPublicationJobTest.Crash();
        };

        JobExecution first = launch("statementJob", cycle);
        publication.beforePublish = NOTHING;

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        Map<String, Object> publishing = runRow(cycle);
        assertThat(publishing).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING")
                .containsEntry("work_folder", cycle + "-1." + first.getId());
        assertThat(Files.exists(finalFolder(cycle))).isFalse();

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedAsAnUninterruptedRun(restart, cycle);
        assertThat(step(restart, "render").getWriteCount()).isZero();
        assertThat(step(restart, "render").getReadCount()).isZero();
        assertThat(runRow(cycle)).containsEntry("work_folder", publishing.get("work_folder"))
                .containsEntry("manifest_sha256", publishing.get("manifest_sha256"));
        assertThat(hashes(finalFolder(cycle))).isEqualTo(published(committed.get()));
    }

    /**
     * The manifest is linked, then the execution ended before COMPLETED. The restart joins the
     * final folder that already holds the legacy file set and {@code .claim}, verifies it the same
     * way and completes; no file is replaced.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem("DEF-STMT-005")
    void aCrashAfterTheManifestAndBeforeCompletedOnlyVerifiesTheLegacyFiles(FidelityMode mode) {
        long cycle = postedSampleCycle();
        AtomicReference<SortedMap<String, FileState>> linked = new AtomicReference<>();
        publication.beforeComplete = execution -> {
            linked.set(states(finalFolder(cycle)));
            throw new StatementPublicationJobTest.Crash();
        };

        JobExecution first = launch("statementJob", cycle);
        publication.beforeComplete = NOTHING;

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "PUBLISHING");
        assertThat(linked.get().keySet()).containsExactlyInAnyOrder(FolderPublication.CLAIM, FolderPublication.MANIFEST,
                StatementFolders.HTML, StatementFolders.TEXT);

        JobExecution restart = launch("statementJob", cycle);

        assertCompletedAsAnUninterruptedRun(restart, cycle);
        assertThat(step(restart, "render").getWriteCount()).isZero();
        assertThat(step(restart, "render").getReadCount()).isZero();
        assertThat(states(finalFolder(cycle))).isEqualTo(linked.get());
    }

    /**
     * Step 1 checks the legacy file set, not the fixed one: a fixed run's {@code index.csv} added
     * to the legacy working folder before the intent fails it with PUBLICATION_MISMATCH, and
     * nothing is published.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem("DEF-STMT-005")
    void aFixedFileInTheLegacyWorkingFolderFailsTheIntent(FidelityMode mode) {
        long cycle = postedSampleCycle();
        publication.beforeIntent = execution -> {
            try {
                Files.write(workFolder(cycle, execution.getId()).resolve(StatementFolders.INDEX),
                        (FixedRenderTasklet.INDEX_HEADER + "\n").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription()).startsWith("PUBLICATION_MISMATCH: ");
        assertThat(runRow(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "RUNNING")
                .containsEntry("work_folder", null);
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
    }
}
