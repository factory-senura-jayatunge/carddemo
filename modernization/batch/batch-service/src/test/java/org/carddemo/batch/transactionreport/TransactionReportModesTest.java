package org.carddemo.batch.transactionreport;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The two report switches through the print step, each mode against its own expected lines. */
@BothModes({"DEF-TRPT-001", "DEF-TRPT-004"})
class TransactionReportModesTest {
    @TempDir Path var;
    ReportTaskletHarness h;
    boolean fixed;
    TransactionReportTasklet tasklet;

    @BeforeEach
    void fixture() {
        h = new ReportTaskletHarness(var);
        FidelityMode mode = FidelityOverrides.current();
        fixed = mode == FidelityMode.FIXED;
        tasklet = h.tasklet(FidelitySwitches.of(BatchSwitches.ALL,
                Map.of("DEF-TRPT-001", mode, "DEF-TRPT-004", mode)));
    }

    @AfterEach
    void clear() {
        h.close();
    }

    static ReportRow row(String tranId, String card, String acct, String amount) {
        return new ReportRow("2022-06-01", tranId, card, acct, "01", "Purchase", "0001", "Regular Sales Draft",
                "POS TERM", new BigDecimal(amount));
    }

    /** {@code +ZZZ,ZZZ,ZZZ.ZZ} of a small positive amount. */
    static String plus(String shown) {
        return "+" + " ".repeat(14 - shown.length()) + shown;
    }

    List<String> publish() throws Exception {
        h.execute(tasklet);
        h.commit();
        h.execute(tasklet);
        Path file = h.root.resolve(ReportPublication.relativePath(ReportTaskletHarness.RUN));
        String text = Files.readString(file, ISO_8859_1);
        return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n"));
    }

    String reportQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(h.db, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues().stream().filter(text -> text.contains("core.[transaction]")).findFirst()
                .orElseThrow();
    }

    @Test
    @SpecItem({"DEF-TRPT-004", "BR-TRPT-004"})
    void accountTotalsFollowTheModesBreakAndOrder() throws Exception {
        var a = row("0000000000000001", "4000000000000001", "00000000001", "1.00");
        var b = row("0000000000000002", "4000000000000002", "00000000002", "2.00");
        var c = row("0000000000000003", "4000000000000003", "00000000001", "4.00");
        // The order each mode's ORDER BY returns: by card, or by account and then card.
        if (fixed) {
            h.input(a, c, b);
        } else {
            h.input(a, b, c);
        }
        List<String> lines = publish();
        assertThat(reportQuery()).endsWith(fixed ? "ORDER BY x.acct_id, t.card_num, t.tran_id"
                : "ORDER BY t.card_num, t.tran_id");
        List<String> totals = lines.stream().filter(text -> text.startsWith("Account Total"))
                .map(text -> text.substring(97, 112)).toList();
        assertThat(totals).containsExactlyElementsOf(fixed ? List.of(plus("5.00"), plus("2.00"))
                : List.of(plus("1.00"), plus("2.00"), plus("4.00")));
        assertThat(lines.getLast()).startsWith("Grand Total");
        assertThat(lines.getLast().substring(97, 112)).isEqualTo(plus("7.00"));
    }

    @Test
    @SpecItem({"DEF-TRPT-001", "FLOW-TRPT-001", "BR-TRPT-006"})
    void anEmptySelectionPrintsNothingInLegacyAndOnlyTheTotalsWhenFixed() throws Exception {
        h.input();
        List<String> lines = publish();
        if (fixed) {
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0)).isEqualTo("Page Total " + ".".repeat(86) + " ".repeat(36));
            assertThat(lines.get(1)).isEqualTo("-".repeat(133));
            assertThat(lines.get(2)).isEqualTo("Grand Total" + ".".repeat(86) + " ".repeat(36));
        } else {
            assertThat(lines).isEmpty();
        }
        assertThat(h.job.getExecutionContext().getString("carddemo.output.report"))
                .isEqualTo("reports/tranrept/42/TRANREPT.txt");
    }

    @Test
    @SpecItem({"DEF-TRPT-001", "FLOW-TRPT-001", "BR-TRPT-002"})
    void theQueryAppliesTheStoredRangeInBothModes() throws Exception {
        h.input();
        publish();
        assertThat(reportQuery()).contains("t.in_master = 1 AND t.proc_date BETWEEN :start AND :end");
    }
}
