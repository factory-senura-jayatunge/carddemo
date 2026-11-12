package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * statementJob in legacy mode on the sample day (batch.md, "Statements (statementJob)"): the files
 * it publishes are the CBSTM03A harness's output byte for byte, and its run row, restart and
 * mismatch behaviour follow "Runs, restart and rerun". The comparisons report lengths, hashes and
 * offsets only.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=legacy"})
class StatementJobTest extends StatementJobTestBase {

    private static final Properties GOLDEN = StatementGoldens.expected("sample-posttran");

    @Autowired
    StatementPublication publication;

    @AfterEach
    void noHook() {
        publication.beforeIntent = execution -> { };
    }

    private byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    private void assertGolden(long cycle) throws IOException {
        Path folder = finalFolder(cycle);
        assertThat(StatementGoldens.difference(GOLDEN, "text", 80, null, read(folder.resolve(StatementFolders.TEXT))))
                .isNull();
        assertThat(StatementGoldens.difference(GOLDEN, "html", 100, null, read(folder.resolve(StatementFolders.HTML))))
                .isNull();
    }

    private static Map<String, Object> run(long cycle) {
        return DB.admin().sql("""
                SELECT statement_run_id, mode, status, watermark_tran_pk, statements, transactions, unassigned, adjusted,
                    job_execution_id, failure_code
                FROM batch.statement_run WHERE cycle_id = :c AND attempt = 1""").param("c", cycle).query().singleRow();
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void theLegacyRunPublishesTheHarnessFilesAndCompletesItsRun() throws IOException {
        long cycle = postedSampleCycle();

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertGolden(cycle);
        assertThat(names(finalFolder(cycle))).contains(StatementFolders.TEXT, StatementFolders.HTML,
                FolderPublication.MANIFEST).doesNotContain(StatementFolders.INDEX);

        Map<String, Object> row = run(cycle);
        assertThat(row).containsEntry("mode", "LEGACY").containsEntry("status", "COMPLETED")
                .containsEntry("statements", Integer.parseInt(GOLDEN.getProperty("statements")))
                .containsEntry("transactions", 262L).containsEntry("unassigned", 0L).containsEntry("adjusted", 0)
                .containsEntry("job_execution_id", execution.getId());
        assertThat(row.get("watermark_tran_pk")).isEqualTo(count("SELECT MAX(tran_pk) FROM core.[transaction]"));
        assertThat(step(execution, "render").getReadCount()).as("work-file records read").isEqualTo(262);
        assertThat(step(execution, "render").getWriteCount()).as("statements written")
                .isEqualTo(Integer.parseInt(GOLDEN.getProperty("statements")));
        // The same capture as fixed mode: every account with at least one cross-reference row, at its balance.
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_capture WHERE statement_run_id = ?",
                row.get("statement_run_id"))).isEqualTo(count("""
                        SELECT COUNT_BIG(*) FROM core.account AS a
                        WHERE EXISTS (SELECT 1 FROM core.card_xref AS x WHERE x.acct_id = a.acct_id)"""))
                .isEqualTo(count("""
                        SELECT COUNT_BIG(*) FROM batch.statement_capture AS c
                        JOIN core.account AS a ON a.acct_id = c.acct_id
                        WHERE c.statement_run_id = ? AND c.curr_bal = a.curr_bal AND c.credit_limit = a.credit_limit""",
                        row.get("statement_run_id")));
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.account_statement WHERE statement_run_id = ?",
                row.get("statement_run_id"))).isZero();
        assertThat(execution.getExecutionContext().get(StatementPublication.OUTPUT))
                .isEqualTo(row.get("statement_run_id"));
        assertThat(((Number) execution.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT)).longValue()).isZero();
        assertThat(names(StatementFolders.workRoot(root()))).as("the working folder is removed once published")
                .isEmpty();
    }

    /**
     * A master row whose card has no cross-reference is in no statement and the files stay the
     * harness's (BR-STMT-009, checked on the files); the run counts it as unassigned and the job
     * ends COMPLETED_WITH_REJECTS (D-57; batch.md, "Build rulings", Q3).
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "BR-STMT-009"})
    void anUnassignedMasterRowLeavesTheFilesAndCountsAsAReject(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES ('9999999999999999', '01', '0001', 'POS TERM', 'unassigned', 1.00, '000000001', 'M', 'C', '1',
                    '9999999999999999', '2101-06-01 00:00:00', '2101-06-01 00:00:00', 'LOADED', 1)""").update();

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExecutionOutputs.COMPLETED_WITH_REJECTS);
        assertThat(((Number) execution.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT)).longValue())
                .isEqualTo(1);
        assertThat(run(cycle)).containsEntry("unassigned", 1L).containsEntry("transactions", 262L);
        assertThat(step(execution, "render").getReadCount()).as("every work-file record, the unassigned one too")
                .isEqualTo(262 + 1);
        assertGolden(cycle);
        assertRunMode(cycle, mode);
    }

    /** A restart that finds the run PUBLISHING claims it, renders nothing and finishes the publication. */
    @Test
    void aRestartAtPublishingFinishesWithoutRendering() throws IOException {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        DB.admin().sql("UPDATE batch.statement_run SET status = 'PUBLISHING', completed_at = NULL WHERE cycle_id = :c")
                .param("c", cycle).update();
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getId()).isNotEqualTo(first.getId());
        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(restart, "render").getWriteCount()).isZero();
        assertThat(step(restart, "render").getReadCount()).isZero();
        assertThat(run(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", restart.getId());
        assertThat(names(StatementFolders.workRoot(root()))).isEmpty();
        assertGolden(cycle);
    }

    /** A restart of a COMPLETED run claims nothing and only checks the published folder. */
    @Test
    void aRestartOfACompletedRunOnlyChecksTheFolder() throws IOException {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", first.getId());
        assertThat(restart.getExecutionContext().get(StatementPublication.OUTPUT))
                .isEqualTo(run(cycle).get("statement_run_id"));
        assertGolden(cycle);
    }

    /**
     * A published file that no longer has the committed bytes fails the restart with
     * PUBLICATION_MISMATCH; the run stays PUBLISHING, now the restart's, and nothing is replaced.
     */
    @Test
    void aMismatchFailsTheRestartAndLeavesTheRunPublishing() throws IOException {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        DB.admin().sql("UPDATE batch.statement_run SET status = 'PUBLISHING', completed_at = NULL WHERE cycle_id = :c")
                .param("c", cycle).update();
        markFailed(first);
        Path text = finalFolder(cycle).resolve(StatementFolders.TEXT);
        byte[] before = read(text);
        try (FileChannel channel = FileChannel.open(text, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (before[0] ^ 1)}), 0);
        }

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restart.getExitStatus().getExitDescription()).startsWith("PUBLICATION_MISMATCH");
        assertThat(run(cycle)).containsEntry("status", "PUBLISHING").containsEntry("job_execution_id", restart.getId());
        assertThat(read(text)[0]).isEqualTo((byte) (before[0] ^ 1));
    }

    /** A run that a rerun or a data load ended is never restarted; the way forward is cycle-rerun. */
    @Test
    void aRestartOfAnInvalidatedRunIsRefused() {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        DB.admin().sql("UPDATE batch.statement_run SET status = 'INVALIDATED' WHERE cycle_id = :c").param("c", cycle)
                .update();
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restart.getExitStatus().getExitDescription()).startsWith(CaptureTasklet.RUN_INVALIDATED);
        assertThat(run(cycle)).containsEntry("status", "INVALIDATED").containsEntry("job_execution_id", first.getId());
    }

    /** A stored watermark above the current highest transaction (the transactions were reloaded) is refused. */
    @Test
    void aRestartWithAWatermarkAboveTheMasterIsRefused() {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        DB.admin().sql("""
                UPDATE batch.statement_run SET status = 'FAILED', failure_code = 'OWNER_LOST', work_folder = NULL,
                    manifest_sha256 = NULL, statements = NULL, transactions = NULL, unassigned = NULL, adjusted = NULL,
                    completed_at = NULL, watermark_tran_pk = watermark_tran_pk + 1000
                WHERE cycle_id = :c""").param("c", cycle).update();
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restart.getExitStatus().getExitDescription()).startsWith(CaptureTasklet.WATERMARK_INVALID);
        assertThat(run(cycle)).containsEntry("status", "FAILED").containsEntry("job_execution_id", first.getId());
    }

    /**
     * A FAILED run (its owner was recovered) is claimed by the restart, rendered again from the
     * same capture and published: the result is the harness's files.
     */
    @Test
    void aRestartOfAFailedRunRendersAgainAndCompletes() throws IOException {
        long cycle = postedSampleCycle();
        JobExecution first = launch("statementJob", cycle);
        long watermark = (Long) run(cycle).get("watermark_tran_pk");
        // The first publication is undone, so the restart publishes onto an empty folder.
        try (var files = Files.walk(finalFolder(cycle))) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        DB.admin().sql("""
                UPDATE batch.statement_run SET status = 'FAILED', failure_code = 'OWNER_LOST', work_folder = NULL,
                    manifest_sha256 = NULL, statements = NULL, transactions = NULL, unassigned = NULL, adjusted = NULL,
                    completed_at = NULL
                WHERE cycle_id = :c""").param("c", cycle).update();
        markFailed(first);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", restart.getId())
                .containsEntry("watermark_tran_pk", watermark).containsEntry("failure_code", null);
        assertGolden(cycle);
    }

    /**
     * A write through a login outside the window guard after the render and before the intent: a
     * balance change and a master row above {@code W}. The execution then ends (a crash). The restart
     * renders from the run's capture, the captured balance and the master up to {@code W}, so its
     * files are still the harness's byte for byte (batch.md, "Build rulings", legacy capture).
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "BR-STMT-007"})
    void aRestartAfterAWriteOutsideTheGuardRendersTheCapturedBalanceAndMaster(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        Map<String, Object> xref = DB.admin().sql("SELECT TOP (1) card_num, acct_id FROM core.card_xref ORDER BY card_num")
                .query().singleRow();
        String acct = (String) xref.get("acct_id");
        BigDecimal balance = DB.admin().sql("SELECT curr_bal FROM core.account WHERE acct_id = :a").param("a", acct)
                .query(BigDecimal.class).single();
        publication.beforeIntent = execution -> {
            DB.admin().sql("UPDATE core.account SET curr_bal = curr_bal + 100.00 WHERE acct_id = :a")
                    .param("a", acct).update();
            masterRow((String) xref.get("card_num"));
            throw new StatementPublicationJobTest.Crash();
        };
        JobExecution first = launch("statementJob", cycle);
        publication.beforeIntent = execution -> { };
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        long watermark = (Long) run(cycle).get("watermark_tran_pk");
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE in_master = 1 AND tran_pk > ?", watermark))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_capture WHERE statement_run_id = ? AND acct_id = ?"
                + " AND curr_bal = ?", run(cycle).get("statement_run_id"), acct, balance)).isEqualTo(1);

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cycle)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", restart.getId())
                .containsEntry("watermark_tran_pk", watermark).containsEntry("transactions", 262L)
                .containsEntry("unassigned", 0L);
        assertGolden(cycle);
        assertRunMode(cycle, mode);
    }

    // ---- DEF-STMT-001 legacy at the job's entry: the table limit is checked before any output ----

    private static final String ORPHAN_CARD = "9999999999999999";

    private int masterRows;

    /** One master row on {@code card}, inside the posted sample's day. */
    private void masterRow(String card) {
        masterRows++;
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, '01', '0001', 'POS TERM', 'table limit', 1.00, '000000001', 'M', 'C', '1',
                    :card, '2101-06-01 00:00:00', '2101-06-01 00:00:00', 'LOADED', 1)""")
                .param("id", String.format("STMTLIM%09d", masterRows)).param("card", card).update();
    }

    private static long masterRowsOf(String card) {
        return count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE in_master = 1 AND card_num = ?", card);
    }

    /**
     * The cross-reference grown to exactly {@code total} cards, each new one on the first card's
     * account and customer, and every cross-reference card given at least one master row.
     */
    private List<String> crossReferenceCardsWithMasterRows(int total) {
        List<String> cards = new ArrayList<>(DB.admin().sql("SELECT card_num FROM core.card_xref ORDER BY card_num")
                .query(String.class).list());
        assertThat(cards.size()).as("the sample's cross-reference cards").isLessThanOrEqualTo(total);
        Map<String, Object> first = DB.admin().sql("SELECT TOP (1) cust_id, acct_id FROM core.card_xref ORDER BY card_num")
                .query().singleRow();
        for (int i = 1; cards.size() < total; i++) {
            String card = String.format("1%015d", i);
            if (count("SELECT COUNT_BIG(*) FROM core.card_xref WHERE card_num = ?", card) > 0) {
                continue;
            }
            DB.admin().sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:card, :cust, :acct)")
                    .param("card", card).param("cust", first.get("cust_id")).param("acct", first.get("acct_id"))
                    .update();
            cards.add(card);
        }
        for (String card : cards) {
            if (masterRowsOf(card) == 0) {
                masterRow(card);
            }
        }
        return cards;
    }

    /** The work file's card slots and the most master rows on one card, counted independently of the job. */
    private static long[] slots() {
        return new long[] {count("SELECT COUNT_BIG(DISTINCT card_num) FROM core.[transaction] WHERE in_master = 1"),
                count("""
                        SELECT MAX(n) FROM (SELECT COUNT_BIG(*) AS n FROM core.[transaction] WHERE in_master = 1
                            GROUP BY card_num) AS c""")};
    }

    /** FAILED with the two counts, no file in the final or a working folder, and no published or completed run. */
    private void assertRefusedBeforeAnyOutput(JobExecution execution, long cycle, long cards, long most) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitDescription())
                .startsWith(LegacyStatementException.STATEMENT_TABLE_LIMIT + ": ")
                .contains("the work file has " + cards + " cards and up to " + most + " transactions on one card")
                .doesNotContainPattern("\\d{11}");
        assertThat(Files.exists(finalFolder(cycle))).isFalse();
        assertThat(names(StatementFolders.workRoot(root()))).isEmpty();
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM batch.statement_run WHERE cycle_id = ?
                    AND (status IN ('PUBLISHING', 'COMPLETED') OR work_folder IS NOT NULL)""", cycle)).isZero();
    }

    /**
     * 51 cross-reference cards with master rows and one card with master rows and no
     * cross-reference row are 52 slots: the orphan takes a slot, as in the program's table.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005", "BR-STMT-009"})
    void fiftyOneCrossReferenceCardsAndAnOrphanCardAreRefusedAsFiftyTwoSlots(FidelityMode mode) {
        long cycle = postedSampleCycle();
        assertThat(crossReferenceCardsWithMasterRows(51)).hasSize(51);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.card_xref WHERE card_num = ?", ORPHAN_CARD)).isZero();
        masterRow(ORPHAN_CARD);
        long[] slots = slots();
        assertThat(slots[0]).isEqualTo(52);

        JobExecution execution = launch("statementJob", cycle);

        assertRefusedBeforeAnyOutput(execution, cycle, 52, slots[1]);
        assertRunMode(cycle, mode);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void fiftyTwoCrossReferenceCardsAreRefused(FidelityMode mode) {
        long cycle = postedSampleCycle();
        crossReferenceCardsWithMasterRows(52);
        long[] slots = slots();
        assertThat(slots[0]).isEqualTo(52);

        JobExecution execution = launch("statementJob", cycle);

        assertRefusedBeforeAnyOutput(execution, cycle, 52, slots[1]);
        assertRunMode(cycle, mode);
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void elevenTransactionsOnOneCardAreRefused(FidelityMode mode) {
        long cycle = postedSampleCycle();
        String card = DB.admin().sql("SELECT MIN(card_num) FROM core.card_xref").query(String.class).single();
        assertThat(masterRowsOf(card)).isLessThanOrEqualTo(11);
        while (masterRowsOf(card) < 11) {
            masterRow(card);
        }
        long[] slots = slots();
        assertThat(slots[1]).isEqualTo(11);

        JobExecution execution = launch("statementJob", cycle);

        assertRefusedBeforeAnyOutput(execution, cycle, slots[0], 11);
        assertRunMode(cycle, mode);
    }

    /** The limit itself is allowed: 51 slots, one of them with 10 transactions, publish and complete. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void fiftyOneCardsWithTenTransactionsOnOneAreWithinTheTable(FidelityMode mode) {
        long cycle = postedSampleCycle();
        List<String> cards = crossReferenceCardsWithMasterRows(51);
        while (masterRowsOf(cards.get(0)) < 10) {
            masterRow(cards.get(0));
        }
        assertThat(slots()).containsExactly(51, 10);

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cycle)).containsEntry("status", "COMPLETED").containsEntry("statements", 51)
                .containsEntry("unassigned", 0L);
        assertThat(names(finalFolder(cycle))).contains(StatementFolders.TEXT, StatementFolders.HTML,
                FolderPublication.MANIFEST);
        assertRunMode(cycle, mode);
    }

    /**
     * The pre-check counts the work file the render reads, the master rows up to {@code W}: after
     * a crash, master rows written above {@code W} through a login outside the window guard, an
     * orphan card that would be the 52nd slot and an 11th row on one card, do not refuse the
     * restart, which renders the 51 captured slots.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void masterRowsAboveTheWatermarkDoNotCountAgainstTheTableOnARestart(FidelityMode mode) {
        long cycle = postedSampleCycle();
        List<String> cards = crossReferenceCardsWithMasterRows(51);
        assertThat(slots()[0]).isEqualTo(51);
        assertThat(slots()[1]).isLessThanOrEqualTo(10);
        publication.beforeIntent = execution -> {
            masterRow(ORPHAN_CARD);
            while (masterRowsOf(cards.get(0)) < 11) {
                masterRow(cards.get(0));
            }
            throw new StatementPublicationJobTest.Crash();
        };
        JobExecution first = launch("statementJob", cycle);
        publication.beforeIntent = execution -> { };
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(slots()).containsExactly(52, 11);
        long watermark = (Long) run(cycle).get("watermark_tran_pk");

        JobExecution restart = launch("statementJob", cycle);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(cycle)).containsEntry("mode", mode.name()).containsEntry("status", "COMPLETED")
                .containsEntry("watermark_tran_pk", watermark).containsEntry("statements", 51)
                .containsEntry("unassigned", 0L)
                .containsEntry("transactions", count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE in_master = 1"
                        + " AND tran_pk <= ?", watermark));
    }
}
