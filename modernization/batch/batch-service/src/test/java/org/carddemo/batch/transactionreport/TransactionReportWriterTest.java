package org.carddemo.batch.transactionreport;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The expected lines are assembled from the column table of programs/CBTRN03C.md section 4.2 and
 * the copybook CVTRA07Y, not from the writer: every literal width below is a PIC clause there.
 */
@SpecItem({"BR-TRPT-003", "BR-TRPT-004", "BR-TRPT-005", "BR-TRPT-006", "NUM-TRPT-001", "NUM-TRPT-002",
        "NUM-TRPT-003", "SE-TRPT-001"})
class TransactionReportWriterTest {
    static final String START = "2022-01-01";
    static final String END = "2022-07-06";
    static final String NAME = line("DALYREPT" + " ".repeat(30) + "Daily Transaction Report" + " ".repeat(17)
            + "Date Range: " + START + " to " + END);
    static final String BLANK = " ".repeat(133);
    static final String COLUMNS = line("Transaction ID" + " ".repeat(3) + "Account ID" + " ".repeat(2)
            + "Transaction Type" + " ".repeat(3) + "Tran Category" + " ".repeat(22) + "Tran Source" + " ".repeat(3)
            + " " + " ".repeat(8) + "Amount");
    static final String RULE = "-".repeat(133);
    static final List<String> HEADER = List.of(NAME, BLANK, COLUMNS, RULE);
    static final String NONE = " ".repeat(15);

    static String line(String text) {
        return text + " ".repeat(133 - text.length());
    }

    static ReportRow row(String procDate, String tranId, String card, String acct, String amount) {
        return new ReportRow(procDate, tranId, card, acct, "03", "Credit", "0001", "Credit to Account", "OPERATOR",
                new BigDecimal(amount));
    }

    static ReportRow row(String tranId, String card, String acct, String amount) {
        return row("2022-06-01", tranId, card, acct, amount);
    }

    static String detail(String tranId, String acct, String edited) {
        return line(tranId + " " + acct + " 03-Credit" + " ".repeat(9) + " 0001-Credit to Account" + " ".repeat(12)
                + " OPERATOR" + " ".repeat(6) + edited);
    }

    /** A total edit: the sign in column 1, the shown digits right-aligned in the other 14. */
    static String signed(char sign, String shown) {
        return sign + " ".repeat(14 - shown.length()) + shown;
    }

    static String accountTotal(String edited) {
        return line("Account Total" + ".".repeat(84) + edited);
    }

    static String pageTotal(String edited) {
        return line("Page Total " + ".".repeat(86) + edited);
    }

    static String grandTotal(String edited) {
        return line("Grand Total" + ".".repeat(86) + edited);
    }

    record Run(List<String> lines, List<Boolean> accepted, long details) { }

    static Run run(boolean skipOutOfRange, boolean breakOnAccount, ReportRow... rows) {
        var out = new ByteArrayOutputStream();
        var writer = new TransactionReportWriter(out, START, END, skipOutOfRange, breakOnAccount);
        List<Boolean> accepted = new ArrayList<>();
        for (ReportRow row : rows) {
            accepted.add(writer.accept(row));
        }
        writer.finish();
        byte[] bytes = out.toByteArray();
        assertThat(bytes.length % 134).isZero();
        String text = new String(bytes, ISO_8859_1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += 134) {
            assertThat(text.charAt(i + 133)).isEqualTo('\n');
            lines.add(text.substring(i, i + 133));
        }
        assertThat(writer.lines()).isEqualTo(lines.size());
        return new Run(lines, accepted, writer.details());
    }

    static List<String> concat(List<?>... parts) {
        List<String> all = new ArrayList<>();
        for (List<?> part : parts) {
            part.forEach(item -> all.add((String) item));
        }
        return all;
    }

    @Test
    void headerColumnsAreTheCopybookPositions() {
        assertThat(NAME.substring(0, 8)).isEqualTo("DALYREPT");
        assertThat(NAME.substring(38, 62)).isEqualTo("Daily Transaction Report");
        assertThat(NAME.substring(79, 91)).isEqualTo("Date Range: ");
        assertThat(NAME.substring(91, 115)).isEqualTo("2022-01-01 to 2022-07-06");
        assertThat(COLUMNS.substring(17, 27)).isEqualTo("Account ID");
        assertThat(COLUMNS.substring(29, 45)).isEqualTo("Transaction Type");
        assertThat(COLUMNS.substring(48, 61)).isEqualTo("Tran Category");
        assertThat(COLUMNS.substring(83, 94)).isEqualTo("Tran Source");
        assertThat(COLUMNS.substring(106, 112)).isEqualTo("Amount");
    }

    @Test
    void oneTransactionMatchesTheSampleRunLineAndGetsAllThreeTotals() {
        var run = run(false, false, row("0000000577826814", "4000000000000001", "00000000050", "-47.88"));
        // The detail line of the sample run quoted in programs/CBTRN03C.md section 4.2.
        assertThat(run.lines().get(4)).isEqualTo(line("0000000577826814 00000000050 03-Credit          "
                + "0001-Credit to Account             OPERATOR      -         47.88"));
        assertThat(run.lines()).containsExactlyElementsOf(concat(HEADER,
                List.of(detail("0000000577826814", "00000000050", "-         47.88"),
                        accountTotal("-         47.88"), RULE, pageTotal("-         47.88"), RULE,
                        grandTotal("-         47.88"))));
        assertThat(run.details()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "183.88        | '         183.88' | '+        183.88'",
        "-47.88        | '-         47.88' | '-         47.88'",
        "1453.87       | '       1,453.87' | '+      1,453.87'",
        "4665.52       | '       4,665.52' | '+      4,665.52'",
        "0.05          | '            .05' | '+           .05'",
        "0.00          | '               ' | '               '",
        "999999999.99  | ' 999,999,999.99' | '+999,999,999.99'",
        "-999999999.99 | '-999,999,999.99' | '-999,999,999.99'"})
    @SpecItem("NUM-TRPT-002")
    void editsAmountsAsTheSpecExamples(String amount, String detailEdit, String totalEdit) {
        assertThat(detailEdit).hasSize(15);
        assertThat(totalEdit).hasSize(15);
        var run = run(false, false, row("0000000000000001", "4000000000000001", "00000000001", amount));
        assertThat(run.lines().get(4).substring(97, 112)).isEqualTo(detailEdit);
        assertThat(run.lines().get(4).substring(112)).isBlank();
        assertThat(run.lines().get(5)).isEqualTo(accountTotal(totalEdit));
        assertThat(run.lines().get(7)).isEqualTo(pageTotal(totalEdit));
        assertThat(run.lines().get(9)).isEqualTo(grandTotal(totalEdit));
    }

    @Test
    void sampleTotalsLineUp() {
        var run = run(false, false, row("0000000000000001", "4000000000000001", "00000000001", "1000.00"),
                row("0000000000000002", "4000000000000001", "00000000001", "453.87"));
        assertThat(run.lines().get(6)).isEqualTo(
                "Account Total...................................................................................."
                        + "+      1,453.87" + " ".repeat(21));
    }

    @Test
    @SpecItem("NUM-TRPT-001")
    void totalsKeepTheLowOrderElevenDigitsAndTheSign() {
        var positive = run(false, false, row("0000000000000001", "4000000000000001", "00000000001", "999999999.99"),
                row("0000000000000002", "4000000000000001", "00000000001", "0.02"));
        assertThat(positive.lines().subList(6, 11)).containsExactly(accountTotal(signed('+', ".01")), RULE,
                pageTotal(signed('+', ".01")), RULE, grandTotal(signed('+', ".01")));
        var negative = run(false, false, row("0000000000000001", "4000000000000001", "00000000001", "-999999999.99"),
                row("0000000000000002", "4000000000000001", "00000000001", "-0.02"));
        assertThat(negative.lines().subList(6, 11)).containsExactly(accountTotal(signed('-', ".01")), RULE,
                pageTotal(signed('-', ".01")), RULE, grandTotal(signed('-', ".01")));
    }

    @Test
    @SpecItem("BR-TRPT-005")
    void theSeventeenthDetailOfOneCardStartsAPage() {
        List<ReportRow> rows = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            String id = String.format("%016d", i);
            rows.add(row(id, "4000000000000001", "00000000001", "1.00"));
            details.add(detail(id, "00000000001", "           1.00"));
        }
        var run = run(false, false, rows.toArray(ReportRow[]::new));
        assertThat(run.lines()).containsExactlyElementsOf(concat(HEADER, details.subList(0, 16),
                List.of(pageTotal(signed('+', "16.00")), RULE), HEADER, details.subList(16, 17),
                List.of(accountTotal(signed('+', "17.00")), RULE, pageTotal(signed('+', "1.00")), RULE,
                        grandTotal(signed('+', "17.00")))));
    }

    @Test
    @SpecItem({"BR-TRPT-005", "DEF-TRPT-007"})
    void anAccountTotalThatStepsOverAMultipleOfTwentyDelaysThePageBreak() {
        List<ReportRow> rows = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            rows.add(row(String.format("%016d", i), i <= 15 ? "4000000000000001" : "4000000000000002",
                    "00000000001", "1.00"));
        }
        var lines = run(false, false, rows.toArray(ReportRow[]::new)).lines();
        // Counter 19 after card 1, 21 after its account total; the next multiple is 40.
        assertThat(lines.get(19)).isEqualTo(accountTotal(signed('+', "15.00")));
        assertThat(lines.subList(0, 40)).noneMatch(text -> text.startsWith("Page Total"));
        assertThat(lines.get(39)).isEqualTo(detail(String.format("%016d", 34), "00000000001", "           1.00"));
        assertThat(lines.get(40)).isEqualTo(pageTotal(signed('+', "34.00")));
        assertThat(lines.subList(42, 46)).isEqualTo(HEADER);
        assertThat(lines.get(46)).isEqualTo(detail(String.format("%016d", 35), "00000000001", "           1.00"));
    }

    @Test
    @SpecItem({"BR-TRPT-003", "NUM-TRPT-003"})
    void descriptionsAreCutAndCodesKeepTheirWidth() {
        var row = new ReportRow("2022-06-01", "0000000000000001", "4000000000000001", "00000000001", "01",
                "Purchase made with an extra long description", "0005",
                "A category description longer than twenty-nine characters", "POS TERM", new BigDecimal("12.00"));
        var lines = run(false, false, row).lines();
        assertThat(lines.get(4)).isEqualTo(line("0000000000000001 00000000001 01-Purchase made w "
                + "0005-A category description longer POS TERM      " + "          12.00"));
    }

    @Test
    void valuesOutsideTheLayoutFailWithoutNamingTheRow() {
        for (String amount : new String[] {"1000000000.00", "-1000000000.00", "0.001"}) {
            assertThatThrownBy(() -> run(false, false,
                    row("0000000000000001", "4000000000000001", "00000000001", amount)))
                    .hasMessage("REPORT_LAYOUT: transaction amount");
        }
        var euro = new ReportRow("2022-06-01", "0000000000000001", "4000000000000001", "00000000001", "01",
                "Purchase", "0005", "Caf\u00e9 \u20ac", "POS TERM", BigDecimal.ONE);
        assertThatThrownBy(() -> run(false, false, euro)).hasMessage("REPORT_LAYOUT: report line");
    }

    @Test
    void aRowNeverShowsItsCardNumber() {
        assertThat(row("0000000000000001", "4000000000000001", "00000000001", "1.00").toString())
                .doesNotContain("4000000000000001").contains("0000000000000001");
    }

    @Test
    @SpecItem({"BR-TRPT-002", "DEF-TRPT-001", "FLOW-TRPT-001"})
    void legacyEndsTheReportAtTheFirstRowOutsideTheRange() {
        var run = run(false, false, row("2022-07-06", "0000000000000001", "4000000000000001", "00000000001", "1.00"),
                row("2022-07-07", "0000000000000002", "4000000000000002", "00000000002", "2.00"),
                row("2022-01-01", "0000000000000003", "4000000000000003", "00000000003", "3.00"));
        assertThat(run.accepted()).containsExactly(true, false, false);
        assertThat(run.lines()).containsExactlyElementsOf(concat(HEADER,
                List.of(detail("0000000000000001", "00000000001", "           1.00"))));
        assertThat(run(false, false, row("2021-12-31", "0000000000000001", "4000000000000001", "00000000001",
                "1.00")).lines()).isEmpty();
        assertThat(run(false, false).lines()).isEmpty();
    }

    @Test
    @SpecItem({"BR-TRPT-002", "DEF-TRPT-001", "FLOW-TRPT-001"})
    void fixedSkipsRowsOutsideTheRangeAndAlwaysWritesTheTotals() {
        var run = run(true, false, row("2022-01-01", "0000000000000001", "4000000000000001", "00000000001", "1.00"),
                row("2022-07-07", "0000000000000002", "4000000000000002", "00000000002", "2.00"),
                row("2022-07-06", "0000000000000003", "4000000000000003", "00000000003", "3.00"));
        assertThat(run.accepted()).containsExactly(true, true, true);
        assertThat(run.lines()).containsExactlyElementsOf(concat(HEADER, List.of(
                detail("0000000000000001", "00000000001", "           1.00"), accountTotal(signed('+', "1.00")),
                RULE, detail("0000000000000003", "00000000003", "           3.00"),
                accountTotal(signed('+', "3.00")), RULE, pageTotal(signed('+', "4.00")), RULE,
                grandTotal(signed('+', "4.00")))));
        assertThat(run.details()).isEqualTo(2);
        // An empty selection: the end-of-file totals alone, zero edited as spaces (NUM-TRPT-002).
        assertThat(run(true, false).lines()).containsExactly(pageTotal(NONE), RULE, grandTotal(NONE));
    }

    @Test
    @SpecItem({"BR-TRPT-004", "DEF-TRPT-004"})
    void legacyTotalsEachCardAndFixedEachAccount() {
        ReportRow[] rows = {
            row("0000000000000001", "4000000000000001", "00000000001", "1.00"),
            row("0000000000000002", "4000000000000002", "00000000001", "2.00"),
            row("0000000000000003", "4000000000000003", "00000000002", "4.00")};
        var legacy = run(false, false, rows).lines();
        assertThat(legacy.stream().filter(text -> text.startsWith("Account Total")))
                .containsExactly(accountTotal(signed('+', "1.00")), accountTotal(signed('+', "2.00")),
                        accountTotal(signed('+', "4.00")));
        var fixed = run(false, true, rows).lines();
        assertThat(fixed.stream().filter(text -> text.startsWith("Account Total")))
                .containsExactly(accountTotal(signed('+', "3.00")), accountTotal(signed('+', "4.00")));
        assertThat(fixed.get(fixed.size() - 1)).isEqualTo(grandTotal(signed('+', "7.00")));
    }
}
