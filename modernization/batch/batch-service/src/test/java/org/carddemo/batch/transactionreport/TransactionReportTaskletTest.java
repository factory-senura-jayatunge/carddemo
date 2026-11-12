package org.carddemo.batch.transactionreport;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.support.TransactionSynchronization;

class TransactionReportTaskletTest {
    static final String CARD = "4000000000000001";
    @TempDir Path var;
    ReportTaskletHarness h;
    TransactionReportTasklet tasklet;

    @BeforeEach
    void fixture() {
        h = new ReportTaskletHarness(var);
        tasklet = h.tasklet(FidelitySwitches.allLegacy(BatchSwitches.ALL));
    }

    @AfterEach
    void clear() {
        h.close();
    }

    static ReportRow row(String tranId, String acct, String amount) {
        return new ReportRow("2022-06-01", tranId, CARD, acct, "01", "Purchase", "0001", "Regular Sales Draft",
                "POS TERM", new BigDecimal(amount));
    }

    Path published() {
        return h.root.resolve(ReportPublication.relativePath(ReportTaskletHarness.RUN));
    }

    /** Computed here rather than by the publication, so a test does not check it against itself. */
    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String sha256(String text) throws Exception {
        return sha256(text.getBytes(ISO_8859_1));
    }

    @Test
    void buildCommitsTheIntentAndOnlyTheNextTransactionPublishesAndCompletes() throws Exception {
        h.input(row("0000000000000001", "00000000001", "10.00"), row("0000000000000002", "00000000001", "5.25"));
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(h.publication.targetExists()).isFalse();
        verify(h.rows).running(ReportTaskletHarness.RUN, 1L);
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
        verify(h.repository).updateExecutionContext(h.job);
        assertThat(h.step.getExecutionContext().getLong("carddemo.tranrept.details")).isEqualTo(2);
        assertThat(h.contribution.getReadCount()).isEqualTo(2);
        // 4 header lines, 2 details, account, page and grand totals with their rules.
        assertThat(h.contribution.getWriteCount()).isEqualTo(11);
        byte[] built = Files.readAllBytes(h.publication.folder().resolve(h.publication.workName(1L)));
        assertThat(built).hasSize(11 * 134);
        String hash = sha256(built);
        h.commit();

        AtomicBoolean visibleAtCompletion = new AtomicBoolean();
        doAnswer(invocation -> {
            visibleAtCompletion.set(Files.exists(published()));
            return null;
        }).when(h.rows).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        assertThat(visibleAtCompletion).isTrue();
        assertThat(Files.readAllBytes(published())).isEqualTo(built);
        assertThat(h.job.getExecutionContext().getString("carddemo.output.report"))
                .isEqualTo("reports/tranrept/42/TRANREPT.txt");
        var ordered = inOrder(h.leases, h.rows, h.repository);
        ordered.verify(h.leases).renew(1L);
        ordered.verify(h.rows).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        ordered.verify(h.repository).updateExecutionContext(h.job);
    }

    @Test
    void anIntentLostWithItsTransactionLeavesNothingForTheContextSave() throws Exception {
        h.input();
        h.execute(tasklet);
        h.finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.WORK)).isFalse();
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.HASH)).isFalse();
        assertThat(h.publication.targetExists()).isFalse();
    }

    @Test
    void aCommittedIntentBuildsNothingAgain() throws Exception {
        h.captured("original capture".getBytes(ISO_8859_1));
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(h.db);
        verify(h.rows, never()).running(anyLong(), anyLong());
        assertThat(Files.readString(published(), ISO_8859_1)).isEqualTo("original capture");
    }

    @Test
    void aRefusedCompletionAfterThePublicationIsRepeatedByTheRetryWithoutReplacingTheFile() throws Exception {
        h.captured("original capture".getBytes(ISO_8859_1));
        String hash = sha256("original capture");
        doThrow(new CatalogueFailure("REPORT_RUN_NOT_RUNNING", "report run 42")).doNothing()
                .when(h.rows).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessage("REPORT_RUN_NOT_RUNNING: report run 42");
        assertThat(h.job.getExecutionContext().containsKey("carddemo.output.report")).isFalse();
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows, times(2)).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        assertThat(Files.readString(published(), ISO_8859_1)).isEqualTo("original capture");
    }

    /**
     * The first form publishes the target; the second is the restart after that completion rolled
     * back, which finds the target already published (ALREADY_PUBLISHED). Both store the same hash.
     */
    @Test
    void bothCompletionFormsStoreTheHashOfThePublishedBytes() throws Exception {
        byte[] bytes = "original capture".getBytes(ISO_8859_1);
        String hash = sha256(bytes);
        h.captured(bytes);
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        Object inode = Files.getAttribute(published(), "unix:ino");
        h.finish(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(h.publication.targetExists()).isTrue();
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows, times(2)).markCompleted(ReportTaskletHarness.RUN, 1L, hash);
        verify(h.rows, never()).completedSha256(anyLong());
        verifyNoInteractions(h.db);
        assertThat(Files.getAttribute(published(), "unix:ino")).isEqualTo(inode);
        assertThat(Files.readAllBytes(published())).isEqualTo(bytes);
    }

    @Test
    void theStoredHashIsTheThirtyTwoBytesOfTheIntentsHex() throws Exception {
        byte[] bytes = "original capture".getBytes(ISO_8859_1);
        assertThat(ReportRunRows.sha256Bytes(sha256(bytes)))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void aLostLeaseCannotPublishOrComplete() throws Exception {
        String work = h.captured("original capture".getBytes(ISO_8859_1));
        when(h.leases.renew(1L)).thenReturn(false);
        assertThatThrownBy(() -> h.execute(tasklet)).isInstanceOf(LeaseLostException.class);
        assertThat(h.keeper.isLost(1L)).isTrue();
        assertThat(h.publication.targetExists()).isFalse();
        assertThat(Files.exists(h.publication.folder().resolve(work))).isTrue();
        verifyNoInteractions(h.db, h.rows);
    }

    @Test
    void thePublishRenewalPrecedesTheInstanceFence() throws Exception {
        h.captured("original capture".getBytes(ISO_8859_1));
        BoundReportRoot roots = mock(BoundReportRoot.class);
        when(roots.root(h.job)).thenReturn(h.root);
        h.execute(h.tasklet(FidelitySwitches.allLegacy(BatchSwitches.ALL), roots));
        var ordered = inOrder(h.leases, roots);
        ordered.verify(h.leases).renew(1L);
        ordered.verify(roots).root(h.job);
    }

    @Test
    void aRunThatIsNotRunningForThisExecutionReadsNothingAndCreatesNoFolder() throws Exception {
        when(h.rows.running(ReportTaskletHarness.RUN, 1L))
                .thenThrow(new CatalogueFailure("REPORT_RUN_NOT_RUNNING", "report run 42"));
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessage("REPORT_RUN_NOT_RUNNING: report run 42");
        verifyNoInteractions(h.db);
        assertThat(h.root.resolve("reports")).doesNotExist();
    }

    @Test
    void anExistingTargetWithoutAnIntentFailsBeforeReadingTheMaster() throws Exception {
        String work = h.captured("already published".getBytes(ISO_8859_1));
        OutputPublication.publishFile(h.publication.work(work), h.publication.target(),
                h.job.getExecutionContext().getString(ReportPublication.HASH));
        h.job.getExecutionContext().remove(ReportPublication.WORK);
        h.job.getExecutionContext().remove(ReportPublication.HASH);
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessageContaining("PUBLICATION_MISMATCH");
        verifyNoInteractions(h.db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"carddemo.tranrept.workPath", "carddemo.tranrept.sha256"})
    void halfAnIntentFailsBeforeAnyStatement(String key) {
        h.job.getExecutionContext().putString(key,
                key.endsWith("workPath") ? h.publication.workName(9) : "0".repeat(64));
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessage("PUBLICATION_MISMATCH: publication refused");
        verifyNoInteractions(h.db, h.leases, h.rows);
        assertThat(h.root.resolve("reports")).doesNotExist();
    }

    @Test
    void anIntentNamingAForeignPathIsRefused() {
        h.job.getExecutionContext().putString(ReportPublication.WORK, "../../PRTCATBL.txt.9.tmp");
        h.job.getExecutionContext().putString(ReportPublication.HASH, "0".repeat(64));
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessage("PUBLICATION_MISMATCH: publication refused");
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
    }

    @Test
    void cleanUpRemovesOnlyTerminalUnreferencedWork() throws Exception {
        h.input();
        var terminal = new JobExecution(2L, h.job.getJobInstance(), new JobParameters());
        terminal.setStatus(BatchStatus.FAILED);
        var named = new JobExecution(3L, h.job.getJobInstance(), new JobParameters());
        named.setStatus(BatchStatus.FAILED);
        named.getExecutionContext().putString(ReportPublication.WORK, h.publication.workName(3));
        for (long id : new long[] {2, 3, 400}) {
            try (var out = h.publication.create(id)) {
                out.write(1);
            }
        }
        when(h.repository.getJobExecutions(h.job.getJobInstance())).thenReturn(List.of(h.job, terminal, named));
        h.execute(tasklet);
        assertThat(h.publication.folder().resolve(h.publication.workName(2))).doesNotExist();
        assertThat(h.publication.folder().resolve(h.publication.workName(3))).exists();
        assertThat(h.publication.folder().resolve(h.publication.workName(400))).exists();
    }

    @Test
    @SpecItem({"BR-TRPT-007", "ERR-TRPT-004", "DEF-TRPT-006"})
    void aMissingLookupFailsTheReportWithoutAnyValueAndCommitsNoIntent() throws Exception {
        var orphan = new ReportRow("2022-06-01", "0000000000000002", "4000000000000009", null, "01", "Purchase", "0001",
                "Regular Sales Draft", "POS TERM", BigDecimal.ONE);
        h.input(row("0000000000000001", "00000000001", "1.00"), orphan);
        assertThatThrownBy(() -> h.execute(tasklet))
                .hasMessage("REPORT_REFERENCE_MISSING: card cross-reference")
                .hasMessageNotContaining("4000000000000009").hasMessageNotContaining("0000000000000002");
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.WORK)).isFalse();
        assertThat(h.publication.targetExists()).isFalse();
    }

    @Test
    @SpecItem({"BR-TRPT-007", "ERR-TRPT-004"})
    void lookupsAreCheckedInTheProgramsOrder() {
        assertThatThrownBy(() -> TransactionReportTasklet.row("2022-06-01", "1", CARD, null, "01", null, "0001", null,
                "S", BigDecimal.ONE)).hasMessage("REPORT_REFERENCE_MISSING: card cross-reference");
        assertThatThrownBy(() -> TransactionReportTasklet.row("2022-06-01", "1", CARD, "1", "01", null, "0001", null,
                "S", BigDecimal.ONE)).hasMessage("REPORT_REFERENCE_MISSING: transaction type");
        assertThatThrownBy(() -> TransactionReportTasklet.row("2022-06-01", "1", CARD, "1", "01", "T", "0001", null,
                "S", BigDecimal.ONE)).hasMessage("REPORT_REFERENCE_MISSING: transaction category");
    }

    RepeatStatus start() {
        var step = new StepExecution(2L, TransactionReportConfiguration.START_STEP, h.job);
        return new StartReportRunTasklet(h.rows).execute(step.createStepContribution(), null);
    }

    @Test
    void startMarksTheRunRunningForThisExecution() throws Exception {
        when(h.rows.markRunning(ReportTaskletHarness.RUN, 1L)).thenReturn(true);
        assertThat(start()).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows).markRunning(ReportTaskletHarness.RUN, 1L);
        verifyNoMoreInteractions(h.rows);
    }

    @Test
    void startRefusesARowItCannotRunWithoutAFinishedPublication() throws Exception {
        assertThatThrownBy(this::start).hasMessage("REPORT_RUN_NOT_RUNNABLE: report run 42");
        h.captured("original capture".getBytes(ISO_8859_1));
        assertThatThrownBy(this::start).as("an intent that never completed the row")
                .hasMessage("REPORT_RUN_NOT_RUNNABLE: report run 42");
        verify(h.rows, never()).completedByEarlier(anyLong(), anyLong(), anyLong());
    }

    @Test
    void startWritesNothingForARowAnEarlierExecutionOfTheInstanceCompleted() throws Exception {
        h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        assertThat(start()).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows).markRunning(ReportTaskletHarness.RUN, 1L);
        verify(h.rows).completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L);
        verifyNoMoreInteractions(h.rows);
    }

    @Test
    void startRefusesAnyOtherCompletedRow() throws Exception {
        h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        assertThatThrownBy(this::start).hasMessage("REPORT_RUN_NOT_RUNNABLE: report run 42");
        h.job.getExecutionContext().putString(ReportPublication.OUTPUT, ReportPublication.relativePath(43));
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        assertThatThrownBy(this::start).as("a carried output of another run")
                .hasMessage("REPORT_RUN_NOT_RUNNABLE: report run 42");
    }

    @Test
    void printOnlyVerifiesAReportAnEarlierExecutionOfTheInstanceCompleted() throws Exception {
        String work = h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        Object inode = Files.getAttribute(published(), "unix:ino");
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        when(h.rows.completedSha256(ReportTaskletHarness.RUN))
                .thenReturn(Optional.of(HexFormat.of().parseHex(sha256("original capture"))));
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(h.db);
        verify(h.rows).completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L);
        verify(h.rows).completedSha256(ReportTaskletHarness.RUN);
        verifyNoMoreInteractions(h.rows);
        verify(h.repository, never()).updateExecutionContext(h.job);
        assertThat(Files.readString(published(), ISO_8859_1)).isEqualTo("original capture");
        assertThat(Files.getAttribute(published(), "unix:ino")).isEqualTo(inode);
        assertThat(h.publication.folder().resolve(work)).doesNotExist();
        assertThat(h.job.getExecutionContext().getString(ReportPublication.OUTPUT))
                .isEqualTo("reports/tranrept/42/TRANREPT.txt");
    }

    @Test
    void printAcceptsARowCompletedBeforeItHadAHashOnTheIntentAlone() throws Exception {
        h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        when(h.rows.completedSha256(ReportTaskletHarness.RUN)).thenReturn(Optional.empty());
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows).completedSha256(ReportTaskletHarness.RUN);
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
        verify(h.repository, never()).updateExecutionContext(h.job);
        assertThat(Files.readString(published(), ISO_8859_1)).isEqualTo("original capture");
    }

    @Test
    void printFailsWhenTheRowHashDiffersFromTheIntentBeforeTouchingAFile() throws Exception {
        String work = h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        boolean workKept = Files.exists(h.publication.folder().resolve(work));
        Object inode = Files.getAttribute(published(), "unix:ino");
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        when(h.rows.completedSha256(ReportTaskletHarness.RUN))
                .thenReturn(Optional.of(HexFormat.of().parseHex(sha256("other capture"))));
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessageContaining("PUBLICATION_MISMATCH");
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
        verify(h.repository, never()).updateExecutionContext(h.job);
        verifyNoInteractions(h.db);
        assertThat(Files.getAttribute(published(), "unix:ino")).isEqualTo(inode);
        assertThat(Files.readString(published(), ISO_8859_1)).isEqualTo("original capture");
        assertThat(Files.exists(h.publication.folder().resolve(work))).isEqualTo(workKept);
    }

    @Test
    void printFailsWhenTheReportAnEarlierExecutionCompletedHasOtherBytes() throws Exception {
        h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        String hash = h.job.getExecutionContext().getString(ReportPublication.HASH);
        h.job.getExecutionContext().putString(ReportPublication.HASH,
                OutputPublication.sha256("other capture".getBytes(ISO_8859_1)));
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(OutputPublication.sha256(published())).isEqualTo(hash);
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
    }

    @Test
    void printNeverPublishesAMissingReportAnEarlierExecutionCompleted() throws Exception {
        String work = h.captured("original capture".getBytes(ISO_8859_1));
        h.job.getExecutionContext().putString(ReportPublication.OUTPUT, ReportPublication.relativePath(42));
        when(h.rows.completedByEarlier(ReportTaskletHarness.RUN, 7L, 1L)).thenReturn(true);
        assertThatThrownBy(() -> h.execute(tasklet)).hasMessageContaining("PUBLICATION_MISMATCH");
        assertThat(h.publication.targetExists()).isFalse();
        assertThat(h.publication.folder().resolve(work)).exists();
        verify(h.rows, never()).markCompleted(anyLong(), anyLong(), any());
    }

    @Test
    void aCarriedOutputKeyAloneDoesNotSkipTheCompletion() throws Exception {
        h.finishedEarlier("original capture".getBytes(ISO_8859_1));
        assertThat(h.execute(tasklet)).isEqualTo(RepeatStatus.FINISHED);
        verify(h.rows).markCompleted(ReportTaskletHarness.RUN, 1L, sha256("original capture"));
    }

    @Test
    void theOutputKeyOfARolledBackCompletionIsNotCarried() throws Exception {
        h.captured("original capture".getBytes(ISO_8859_1));
        h.execute(tasklet);
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.OUTPUT)).isTrue();
        h.finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.OUTPUT)).isFalse();
        assertThat(h.job.getExecutionContext().containsKey(ReportPublication.WORK)).isTrue();
    }

    @Test
    void theCompletedCommitHookRunsOnlyAfterTheCommit() throws Exception {
        h.captured("original capture".getBytes(ISO_8859_1));
        AtomicInteger calls = new AtomicInteger();
        tasklet.afterCompletedCommit = job -> calls.incrementAndGet();
        h.execute(tasklet);
        assertThat(calls).hasValue(0);
        h.commit();
        assertThat(calls).hasValue(1);
        assertThat(h.job.getExecutionContext().getString(ReportPublication.OUTPUT))
                .isEqualTo("reports/tranrept/42/TRANREPT.txt");
    }

    @Test
    void anExecutionWithoutRunIdIsNotRunnable() {
        var bare = new JobExecution(5L, h.job.getJobInstance(), new JobParameters());
        assertThatThrownBy(() -> TransactionReportConfiguration.runId(bare))
                .hasMessage("REPORT_RUN_NOT_RUNNABLE: no runId");
    }
}
