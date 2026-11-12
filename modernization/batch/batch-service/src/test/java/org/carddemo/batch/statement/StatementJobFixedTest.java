package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * statementJob in fixed mode over consecutive cycles (batch.md, "Which accounts and transactions
 * (fixed)", "Dates, balances and the reconciled total"; "Tests (statement job)", fixed): the second
 * statement opens at the first one's closing balance, every row is on exactly one statement, a
 * late entry lands on the next statement, a balance edited without a row gives the adjustment
 * line and COMPLETED_WITH_REJECTS, an unassigned row is counted, an empty period still prints, and
 * an account new since the previous run gets a first statement. The PDFs are read back with
 * PDFTextStripper. The rows added here are made up; the sample cycle is posted as in the other
 * statement tests.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=fixed"})
class StatementJobFixedTest extends StatementJobTestBase {

    private static final String SECOND_DATE = "2101-06-02";
    private static final String ORPHAN_CARD = "9999999999999999";

    private int added;

    @Autowired
    StatementPublication publication;

    @AfterEach
    void noHook() {
        publication.beforeIntent = execution -> { };
    }

    /** A transaction row committed now, outside any job, with the given processing time. */
    private void row(String card, String procTs, String amount, String description) {
        added++;
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, '01', '0001', 'POS TERM', :description, :amount, '000000001', 'M', 'C', '1',
                    :card, :ts, :ts, 'POSTED', 1)""")
                .param("id", String.format("STMTFIX%09d", added)).param("description", description)
                .param("amount", new BigDecimal(amount)).param("card", card).param("ts", procTs).update();
    }

    private static void addToBalance(String acctId, String amount) {
        DB.admin().sql("UPDATE core.account SET curr_bal = curr_bal + :a WHERE acct_id = :id")
                .param("a", new BigDecimal(amount)).param("id", acctId).update();
    }

    /** The first cross-reference row: its card and account. */
    private static Map<String, Object> firstCrossReference() {
        return DB.admin().sql("SELECT TOP (1) card_num, acct_id FROM core.card_xref ORDER BY card_num").query()
                .singleRow();
    }

    /** The sample cycle posted and its statements published: the first statement of every account. */
    private long firstCycle() {
        long cycle = postedSampleCycle();
        JobExecution execution = launch("statementJob", cycle);
        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        return cycle;
    }

    /** The first cycle's window closed and the next day's cycle registered with its window open. */
    private long nextCycle(long previous) {
        windows.close(WindowHolder.cycle(previous), true);
        return openCycle(SECOND_DATE);
    }

    private static Map<String, Object> run(long cycle) {
        return DB.admin().sql("""
                SELECT statement_run_id, previous_run_id, status, statements, transactions, unassigned, adjusted,
                    watermark_tran_pk
                FROM batch.statement_run WHERE cycle_id = :c AND attempt = 1""").param("c", cycle).query().singleRow();
    }

    private static long id(Map<String, Object> run) {
        return ((Number) run.get("statement_run_id")).longValue();
    }

    private static BigDecimal money(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    private static long sum(Map<String, Map<String, Object>> rows, String column) {
        return rows.values().stream().mapToLong(r -> ((Number) r.get(column)).longValue()).sum();
    }

    private String pdfText(long cycle, String acctId) {
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(finalFolder(cycle).resolve(acctId + ".pdf")))) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Two consecutive periods: {@code O₂ = C₁} for every account, the second period runs from the
     * day after the first statement date, and each row up to the second watermark is on exactly one
     * statement or counted unassigned. The late entry (committed after the first capture, dated
     * before the first statement date) is on the second statement with its own date; every other
     * account's empty period still prints.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-010"})
    void consecutivePeriodsChainAndPutEveryRowOnExactlyOneStatement(FidelityMode mode) {
        long first = firstCycle();
        Map<String, Object> run1 = run(first);
        Map<String, Map<String, Object>> rows1 = accountRows(id(run1));
        long second = nextCycle(first);
        Map<String, Object> xref = firstCrossReference();
        String acct = (String) xref.get("acct_id");
        row((String) xref.get("card_num"), "2101-05-15 12:00:00", "12.34", "late entry");
        addToBalance(acct, "12.34");

        JobExecution execution = launch("statementJob", second);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> run2 = run(second);
        assertThat(run2).containsEntry("status", "COMPLETED").containsEntry("previous_run_id", id(run1))
                .containsEntry("unassigned", 0L).containsEntry("adjusted", 0).containsEntry("transactions", 1L);
        Map<String, Map<String, Object>> rows2 = accountRows(id(run2));
        assertThat(rows2.keySet()).containsExactlyElementsOf(rows1.keySet());
        rows2.forEach((id, row) -> {
            Map<String, Object> before = rows1.get(id);
            assertThat(money(row, "opening_balance")).as(id).isEqualByComparingTo(money(before, "closing_balance"));
            assertThat(String.valueOf(row.get("period_start"))).as(id).isEqualTo(SECOND_DATE);
            assertThat(String.valueOf(row.get("period_end"))).as(id).isEqualTo(SECOND_DATE);
            assertThat(money(row, "adjustment_amount")).as(id).isZero();
            assertThat(Files.exists(finalFolder(second).resolve(id + ".pdf"))).as(id).isTrue();
            if (id.equals(acct)) {
                assertThat(row.get("tran_count")).isEqualTo(1);
                assertThat(money(row, "net_amount")).isEqualByComparingTo("12.34");
                assertThat(money(row, "closing_balance"))
                        .isEqualByComparingTo(money(before, "closing_balance").add(new BigDecimal("12.34")));
            } else {
                assertThat(row.get("tran_count")).as(id).isEqualTo(0);
                assertThat(money(row, "net_amount")).as(id).isZero();
            }
        });
        assertThat(pdfText(second, acct)).contains("late entry");
        assertThat(pdfText(first, acct)).doesNotContain("late entry");
        assertThat(sum(rows1, "tran_count") + sum(rows2, "tran_count") + ((Number) run1.get("unassigned")).longValue()
                + ((Number) run2.get("unassigned")).longValue())
                .isEqualTo(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE tran_pk <= ?",
                        run2.get("watermark_tran_pk")));
        assertRunMode(first, mode);
        assertRunMode(second, mode);
    }

    /**
     * After the render and before the intent, a write through a login outside the window guard
     * inserts a row and changes {@code curr_bal}, and the execution ends (a crash). The restart keeps
     * {@code C} and {@code W}: the same files and account rows. The row is on the next statement,
     * which balances without an adjustment line (batch.md, "Tests (statement job)", capture).
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aRestartAfterAWriteOutsideTheGuardKeepsTheCaptureAndTheRowGoesOnTheNextStatement(FidelityMode mode) {
        long first = postedSampleCycle();
        Map<String, Object> xref = firstCrossReference();
        String acct = (String) xref.get("acct_id");
        AtomicReference<SortedMap<String, String>> rendered = new AtomicReference<>();
        AtomicReference<Map<String, Map<String, Object>>> rows = new AtomicReference<>();
        publication.beforeIntent = execution -> {
            rendered.set(hashes(workFolder(first, execution.getId())));
            rows.set(accountRows(runId(first)));
            row((String) xref.get("card_num"), "2101-06-01 12:00:00", "7.00", "outside the guard");
            addToBalance(acct, "7.00");
            throw new StatementPublicationJobTest.Crash();
        };
        JobExecution crashed = launch("statementJob", first);
        publication.beforeIntent = execution -> { };
        assertThat(crashed.getStatus()).isEqualTo(BatchStatus.FAILED);
        long watermark = ((Number) run(first).get("watermark_tran_pk")).longValue();
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE tran_pk > ?", watermark)).isEqualTo(1);

        JobExecution restart = launch("statementJob", first);

        assertThat(restart.getStatus()).as(restart.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> run1 = run(first);
        assertThat(run1).containsEntry("status", "COMPLETED").containsEntry("watermark_tran_pk", watermark)
                .containsEntry("transactions", 262L).containsEntry("adjusted", 0);
        var render = restart.getStepExecutions().stream().filter(s -> s.getStepName().equals("render")).findFirst()
                .orElseThrow();
        assertThat(render.getReadCount()).as("every account rendered again, the row above W excluded")
                .isEqualTo(262);
        assertThat(render.getWriteCount()).as("the 50 sample accounts").isEqualTo(50);
        assertThat(hashes(finalFolder(first))).isEqualTo(published(rendered.get()));
        assertThat(accountRows(id(run1))).isEqualTo(rows.get());
        BigDecimal closing1 = money(rows.get().get(acct), "closing_balance");

        long second = nextCycle(first);
        JobExecution next = launch("statementJob", second);

        assertThat(next.getStatus()).as(next.getExitStatus().getExitDescription()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(next.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> row = accountRows(id(run(second))).get(acct);
        assertThat(row.get("tran_count")).isEqualTo(1);
        assertThat(money(row, "opening_balance")).isEqualByComparingTo(closing1);
        assertThat(money(row, "net_amount")).isEqualByComparingTo("7.00");
        assertThat(money(row, "adjustment_amount")).isZero();
        assertThat(money(row, "closing_balance")).isEqualByComparingTo(closing1.add(new BigDecimal("7.00")));
        assertThat(pdfText(second, acct)).contains("outside the guard");
        assertThat(pdfText(first, acct)).doesNotContain("outside the guard");
        assertRunMode(first, mode);
        assertRunMode(second, mode);
    }

    /** A balance changed without a row: the next statement prints {@code A} on its adjustment line and the run counts it. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void aBalanceEditedWithoutARowGivesTheAdjustmentLineAndCompletedWithRejects(FidelityMode mode) {
        long first = firstCycle();
        Map<String, Map<String, Object>> rows1 = accountRows(id(run(first)));
        long second = nextCycle(first);
        String acct = (String) firstCrossReference().get("acct_id");
        addToBalance(acct, "5.00");

        JobExecution execution = launch("statementJob", second);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExecutionOutputs.COMPLETED_WITH_REJECTS);
        assertThat(((Number) execution.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT)).longValue())
                .isEqualTo(1);
        assertThat(run(second)).containsEntry("adjusted", 1).containsEntry("unassigned", 0L);
        Map<String, Object> row = accountRows(id(run(second))).get(acct);
        BigDecimal closing1 = money(rows1.get(acct), "closing_balance");
        assertThat(money(row, "opening_balance")).isEqualByComparingTo(closing1);
        assertThat(money(row, "net_amount")).isZero();
        assertThat(money(row, "adjustment_amount")).isEqualByComparingTo("5.00");
        assertThat(money(row, "closing_balance")).isEqualByComparingTo(closing1.add(new BigDecimal("5.00")));
        assertThat(pdfText(second, acct)).contains(StatementPdf.ADJUSTMENT);
        assertThat(pdfText(first, acct)).doesNotContain(StatementPdf.ADJUSTMENT);
        assertRunMode(first, mode);
        assertRunMode(second, mode);
    }

    /** A row whose card has no cross-reference row is on no statement, is counted, and makes the run COMPLETED_WITH_REJECTS. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "BR-STMT-009"})
    void anUnassignedRowIsCounted(FidelityMode mode) {
        long cycle = postedSampleCycle();
        assertThat(count("SELECT COUNT_BIG(*) FROM core.card_xref WHERE card_num = ?", ORPHAN_CARD)).isZero();
        row(ORPHAN_CARD, "2101-06-01 00:00:00", "1.00", "unassigned");

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExecutionOutputs.COMPLETED_WITH_REJECTS);
        assertThat(((Number) execution.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT)).longValue())
                .isEqualTo(1);
        Map<String, Object> run = run(cycle);
        assertThat(run).containsEntry("unassigned", 1L).containsEntry("transactions", 262L).containsEntry("adjusted", 0);
        assertThat(sum(accountRows(id(run)), "tran_count")).isEqualTo(262);
        assertThat(execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("render")).findFirst()
                .orElseThrow().getReadCount()).as("rows listed on a statement, the unassigned one not").isEqualTo(262);
        assertRunMode(cycle, mode);
    }

    /** An account that is new since the previous run gets a first statement: {@code O = C − N}, no adjustment line, not adjusted. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem("DEF-STMT-005")
    void anAccountNewSinceThePreviousRunGetsAFirstStatement(FidelityMode mode) {
        long first = firstCycle();
        long second = nextCycle(first);
        String acct = "00000099999";
        assertThat(count("SELECT COUNT_BIG(*) FROM core.account WHERE acct_id = ?", acct)).isZero();
        DB.fixtures().account(CoreFixtures.Account.of(acct, "40.00")).customer("999999999")
                .card("4999999999990001", acct, "999999999");
        row("4999999999990001", "2101-06-02 09:00:00", "15.00", "first purchase");

        JobExecution execution = launch("statementJob", second);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(run(second)).containsEntry("adjusted", 0).containsEntry("unassigned", 0L);
        assertThat(accountRows(id(run(first)))).doesNotContainKey(acct);
        Map<String, Object> row = accountRows(id(run(second))).get(acct);
        assertThat(row).isNotNull();
        assertThat(money(row, "closing_balance")).isEqualByComparingTo("40.00");
        assertThat(money(row, "net_amount")).isEqualByComparingTo("15.00");
        assertThat(money(row, "opening_balance")).isEqualByComparingTo("25.00");
        assertThat(money(row, "adjustment_amount")).isZero();
        assertThat(String.valueOf(row.get("period_start"))).isEqualTo(SECOND_DATE);
        assertThat(row.get("tran_count")).isEqualTo(1);
        assertThat(pdfText(second, acct)).contains("first purchase").doesNotContain(StatementPdf.ADJUSTMENT);
        assertRunMode(first, mode);
        assertRunMode(second, mode);
    }

    /**
     * No table limit in fixed mode (DEF-STMT-001 fixed): 60 accounts added to the sample, so more
     * than 51, and 11 rows on one card. Every captured account gets its PDF and its account row,
     * and the 11 rows are all on their statement.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void moreThanFiftyOneAccountsAndElevenRowsOnOneCardAllGetStatements(FidelityMode mode) {
        long cycle = postedSampleCycle();
        DB.fixtures().customer("999999998");
        for (int i = 1; i <= 60; i++) {
            String acct = String.format("0000009%04d", i);
            DB.fixtures().account(CoreFixtures.Account.of(acct, "11.00")).card(String.format("49999999%08d", i), acct,
                    "999999998");
        }
        String card = String.format("49999999%08d", 1);
        for (int i = 1; i <= 11; i++) {
            row(card, String.format("2101-06-01 %02d:00:00", i), "1.00", String.format("eleven rows %02d", i));
        }
        long accounts = count("""
                SELECT COUNT_BIG(*) FROM core.account AS a
                WHERE EXISTS (SELECT 1 FROM core.card_xref AS x WHERE x.acct_id = a.acct_id)""");
        assertThat(accounts).isGreaterThan(51);

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> run = run(cycle);
        assertThat(run).containsEntry("status", "COMPLETED").containsEntry("statements", (int) accounts)
                .containsEntry("transactions", 262L + 11).containsEntry("unassigned", 0L);
        assertThat(accounts).as("more accounts than one render chunk").isGreaterThan(FixedRenderTasklet.CHUNK);
        var render = execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("render")).findFirst()
                .orElseThrow();
        assertThat(render.getReadCount()).as("rows listed on a statement, over every chunk").isEqualTo(262 + 11);
        assertThat(render.getWriteCount()).as("statements written, over every chunk").isEqualTo(accounts);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name());
        Map<String, Map<String, Object>> rows = accountRows(id(run));
        assertThat(rows).hasSize((int) accounts);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.statement_capture WHERE statement_run_id = ?", id(run)))
                .isEqualTo(accounts);
        for (String acct : rows.keySet()) {
            assertThat(Files.exists(finalFolder(cycle).resolve(acct + ".pdf"))).as(acct).isTrue();
        }
        assertThat(names(finalFolder(cycle)).stream().filter(n -> n.endsWith(".pdf")).count()).isEqualTo(accounts);
        assertThat(rows.get("00000090001").get("tran_count")).isEqualTo(11);
        String text = pdfText(cycle, "00000090001");
        for (int i = 1; i <= 11; i++) {
            assertThat(text).contains(String.format("eleven rows %02d", i));
        }
    }

    /**
     * No HTML in fixed mode (DEF-STMT-007 fixed): the published folder and the whole statements
     * folder hold no {@code .html} file, and a description with markup characters prints as those
     * characters in the PDF, not as markup.
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "FIXED")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-007"})
    void noHtmlIsWrittenAndMarkupCharactersPrintAsText(FidelityMode mode) throws IOException {
        long cycle = postedSampleCycle();
        Map<String, Object> xref = firstCrossReference();
        String markup = "<b>Tom & Jerry</b> <i>&amp;</i>";
        row((String) xref.get("card_num"), "2101-06-01 12:00:00", "1.00", markup);

        JobExecution execution = launch("statementJob", cycle);

        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(cycle)).containsEntry("mode", mode.name());
        assertThat(names(finalFolder(cycle))).doesNotContain(StatementFolders.HTML)
                .noneMatch(n -> n.toLowerCase(Locale.ROOT).endsWith(".html"));
        try (Stream<Path> paths = Files.walk(StatementFolders.statements(root()))) {
            assertThat(paths.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .noneMatch(n -> n.endsWith(".html") || n.endsWith(".htm"));
        }
        assertThat(pdfText(cycle, (String) xref.get("acct_id"))).contains(markup);
    }
}
