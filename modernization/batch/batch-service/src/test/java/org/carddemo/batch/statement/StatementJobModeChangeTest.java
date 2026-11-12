package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The first fixed statement after legacy runs (batch.md, "Period set", "First statement, per
 * account", "Opening balance O", amended 2026-11-11; "Tests (statement job)", after legacy runs):
 * two legacy cycles, then a fixed one. The fixed run's previous run is the second legacy run, its
 * period set is {@code (W_legacy, W]}, and each account's opening balance is that run's captured
 * {@code curr_bal}; an account with no legacy capture row gets a first statement. A SUPERSEDED or
 * INVALIDATED legacy run is never the previous run. DEF-STMT-005 is switched between the cycles
 * through {@link FidelityOverrides}, which every new capture reads; the rows added here are made up,
 * and the rows added between the cycles are history rows ({@code in_master = 0}), which keeps the
 * legacy runs inside the program's table.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.DEF-STMT-005=legacy"})
class StatementJobModeChangeTest extends StatementJobTestBase {

    private static final String SECOND_DATE = "2101-06-02";
    private static final String THIRD_DATE = "2101-06-03";

    private int added;

    @BeforeEach
    void legacyFirst() {
        FidelityOverrides.set(List.of(BatchSwitches.DEF_STMT_005.itemId()), FidelityMode.LEGACY);
    }

    @AfterEach
    void configuredMode() {
        FidelityOverrides.clear();
    }

    /** A history row ({@code in_master = 0}) committed now, outside any job. */
    private void row(String card, String procTs, String amount, String description) {
        added++;
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, '01', '0001', 'POS TERM', :description, :amount, '000000001', 'M', 'C', '1',
                    :card, :ts, :ts, 'POSTED', 0)""")
                .param("id", String.format("STMTMOD%09d", added)).param("description", description)
                .param("amount", new BigDecimal(amount)).param("card", card).param("ts", procTs).update();
    }

    private static void addToBalance(String acctId, String amount) {
        DB.admin().sql("UPDATE core.account SET curr_bal = curr_bal + :a WHERE acct_id = :id")
                .param("a", new BigDecimal(amount)).param("id", acctId).update();
    }

    private JobExecution completed(long cycle) {
        JobExecution execution = launch("statementJob", cycle);
        assertThat(execution.getStatus()).as(execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /** The previous cycle's window closed and a new cycle registered on {@code date} with its window open. */
    private long nextCycle(long previous, String date) {
        windows.close(WindowHolder.cycle(previous), true);
        return openCycle(date);
    }

    private static long watermark(long cycle) {
        return ((Number) runRow(cycle).get("watermark_tran_pk")).longValue();
    }

    /** A run's captured balances by account. */
    private static Map<String, BigDecimal> captured(long runId) {
        Map<String, BigDecimal> balances = new HashMap<>();
        DB.admin().sql("SELECT acct_id, curr_bal FROM batch.statement_capture WHERE statement_run_id = :r")
                .param("r", runId).query().listOfRows()
                .forEach(row -> balances.put((String) row.get("acct_id"), (BigDecimal) row.get("curr_bal")));
        return balances;
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
     * Legacy, legacy, then fixed. The fixed statement lists only the rows above the second legacy
     * run's {@code W}: the row added between the legacy cycles is not on it. Every account opens at
     * the second legacy run's captured balance, and with no balance edit none is adjusted. The
     * account added before the fixed capture has no legacy capture row and gets a first statement.
     */
    @Test
    @SpecItem("DEF-STMT-005")
    void theFirstFixedStatementAfterLegacyRunsOpensAtTheLegacyCapture() {
        long first = postedSampleCycle();
        completed(first);
        Map<String, Object> xref = DB.admin().sql("SELECT TOP (1) card_num, acct_id FROM core.card_xref ORDER BY card_num")
                .query().singleRow();
        String card = (String) xref.get("card_num");
        String acct = (String) xref.get("acct_id");

        long second = nextCycle(first, SECOND_DATE);
        row(card, "2101-06-02 10:00:00", "3.00", "between the legacy runs");
        addToBalance(acct, "3.00");
        completed(second);
        Map<String, Object> legacy = runRow(second);
        assertThat(legacy).containsEntry("mode", "LEGACY").containsEntry("status", "COMPLETED")
                .containsEntry("previous_run_id", null);
        long legacyRun = runId(second);
        long legacyWatermark = watermark(second);
        Map<String, BigDecimal> legacyBalances = captured(legacyRun);

        long third = nextCycle(second, THIRD_DATE);
        FidelityOverrides.set(List.of(BatchSwitches.DEF_STMT_005.itemId()), FidelityMode.FIXED);
        row(card, "2101-06-03 10:00:00", "4.00", "after the legacy runs");
        addToBalance(acct, "4.00");
        String newAccount = "00000099999";
        assertThat(count("SELECT COUNT_BIG(*) FROM core.account WHERE acct_id = ?", newAccount)).isZero();
        DB.fixtures().account(CoreFixtures.Account.of(newAccount, "40.00")).customer("999999999")
                .card("4999999999990001", newAccount, "999999999");
        row("4999999999990001", "2101-06-02 23:00:00", "15.00", "first purchase");

        JobExecution execution = completed(third);

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> run = runRow(third);
        assertThat(run).containsEntry("mode", "FIXED").containsEntry("status", "COMPLETED")
                .containsEntry("previous_run_id", legacyRun).containsEntry("adjusted", 0)
                .containsEntry("unassigned", 0L).containsEntry("transactions", 2L);
        Map<String, Map<String, Object>> rows = accountRows(runId(third));
        assertThat(sum(rows, "tran_count")).isEqualTo(count(
                "SELECT COUNT_BIG(*) FROM core.[transaction] WHERE tran_pk > ? AND tran_pk <= ?", legacyWatermark,
                run.get("watermark_tran_pk"))).isEqualTo(2);
        assertThat(legacyBalances).doesNotContainKey(newAccount);
        rows.forEach((id, row) -> {
            assertThat(money(row, "adjustment_amount")).as(id).isZero();
            if (id.equals(newAccount)) {
                return;
            }
            assertThat(money(row, "opening_balance")).as(id).isEqualByComparingTo(legacyBalances.get(id));
            assertThat(String.valueOf(row.get("period_start"))).as(id).isEqualTo(THIRD_DATE);
            if (!id.equals(acct)) {
                assertThat(row.get("tran_count")).as(id).isEqualTo(0);
                assertThat(money(row, "closing_balance")).as(id).isEqualByComparingTo(legacyBalances.get(id));
            }
        });
        assertThat(rows.keySet()).containsAll(legacyBalances.keySet()).contains(newAccount);
        Map<String, Object> mine = rows.get(acct);
        assertThat(mine.get("tran_count")).isEqualTo(1);
        assertThat(money(mine, "net_amount")).isEqualByComparingTo("4.00");
        assertThat(money(mine, "closing_balance")).isEqualByComparingTo(legacyBalances.get(acct).add(new BigDecimal("4.00")));
        assertThat(pdfText(third, acct)).contains("after the legacy runs").doesNotContain("between the legacy runs")
                .doesNotContain(StatementPdf.ADJUSTMENT);

        Map<String, Object> firstStatement = rows.get(newAccount);
        assertThat(money(firstStatement, "closing_balance")).isEqualByComparingTo("40.00");
        assertThat(money(firstStatement, "net_amount")).isEqualByComparingTo("15.00");
        assertThat(money(firstStatement, "opening_balance")).isEqualByComparingTo("25.00");
        assertThat(String.valueOf(firstStatement.get("period_start"))).isEqualTo(SECOND_DATE);
        assertThat(firstStatement.get("tran_count")).isEqualTo(1);
        assertThat(pdfText(third, newAccount)).contains("first purchase").doesNotContain(StatementPdf.ADJUSTMENT);
    }

    /**
     * The second legacy run ended by a rerun or a data load (set here as cycle-rerun and
     * dataLoadJob set it) is not the previous run: the fixed run opens at the first legacy run, and
     * its period set starts after that run's {@code W}, so the row added between the legacy runs is on it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SUPERSEDED", "INVALIDATED"})
    @SpecItem("DEF-STMT-005")
    void anEndedLegacyRunIsNeverThePreviousRun(String ended) {
        long first = postedSampleCycle();
        completed(first);
        long firstRun = runId(first);
        long firstWatermark = watermark(first);
        Map<String, BigDecimal> firstBalances = captured(firstRun);
        Map<String, Object> xref = DB.admin().sql("SELECT TOP (1) card_num, acct_id FROM core.card_xref ORDER BY card_num")
                .query().singleRow();
        String card = (String) xref.get("card_num");
        String acct = (String) xref.get("acct_id");

        long second = nextCycle(first, SECOND_DATE);
        row(card, "2101-06-02 10:00:00", "3.00", "between the legacy runs");
        addToBalance(acct, "3.00");
        completed(second);
        DB.admin().sql("UPDATE batch.statement_run SET status = :s WHERE cycle_id = :c").param("s", ended)
                .param("c", second).update();

        long third = nextCycle(second, THIRD_DATE);
        FidelityOverrides.set(List.of(BatchSwitches.DEF_STMT_005.itemId()), FidelityMode.FIXED);
        row(card, "2101-06-03 10:00:00", "4.00", "after the legacy runs");
        addToBalance(acct, "4.00");

        JobExecution execution = completed(third);

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        Map<String, Object> run = runRow(third);
        assertThat(run).containsEntry("mode", "FIXED").containsEntry("previous_run_id", firstRun)
                .containsEntry("adjusted", 0).containsEntry("transactions", 2L);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE tran_pk > ? AND tran_pk <= ?",
                firstWatermark, run.get("watermark_tran_pk"))).isEqualTo(2);
        Map<String, Object> mine = accountRows(runId(third)).get(acct);
        assertThat(money(mine, "opening_balance")).isEqualByComparingTo(firstBalances.get(acct));
        assertThat(money(mine, "net_amount")).isEqualByComparingTo("7.00");
        assertThat(money(mine, "adjustment_amount")).isZero();
        assertThat(String.valueOf(mine.get("period_start"))).isEqualTo(SECOND_DATE);
        assertThat(pdfText(third, acct)).contains("between the legacy runs").contains("after the legacy runs");
    }
}
