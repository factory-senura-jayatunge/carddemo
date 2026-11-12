package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Properties;

import org.carddemo.batch.statement.LegacyStatementRenderer.Rendered;
import org.carddemo.batch.statement.StatementGoldens.Inputs;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The legacy renderer against the original CBSTM03A (CBSTM03A section 15): every expected output
 * was written by the program itself through {@code checks/cbstm03a_harness}, never by this
 * renderer. Failures report offsets, record numbers and hashes, never the content.
 *
 * <p>The legacy renderer is what DEF-STMT-005 legacy selects, so the tests that cite DEF-STMT-005
 * and the items it covers (DEF-STMT-001, 002, 003, 004 and 007; BR-STMT-005, 006 and 008;
 * DEF-JSTMT-005) take the mode as their argument and run in LEGACY here. Their fixed side is the
 * PDF renderer's tests.
 */
class LegacyStatementRendererGoldenTest {

    private static void assertHarnessOutput(String name, Rendered out) {
        Properties expected = StatementGoldens.expected(name);
        assertThat(StatementGoldens.difference(expected, "text", 80,
                StatementGoldens.expectedOutput(name, "STMTFILE"), out.text())).isNull();
        assertThat(StatementGoldens.difference(expected, "html", 100,
                StatementGoldens.expectedOutput(name, "HTMLFILE"), out.html())).isNull();
        assertThat(out.result().statements()).isEqualTo(Integer.parseInt(expected.getProperty("statements")));
    }

    /** One statement per card with the legacy name, address and HTML lines, for the 50 sample cards. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "BR-STMT-005", "BR-STMT-006", "BR-STMT-008"})
    void theSampleAfterPosttranIsByteIdenticalToTheHarnessDefault(FidelityMode mode) {
        Properties expected = StatementGoldens.expected("sample-posttran");
        Rendered out = StatementGoldens.sampleAfterPosttran().render();

        assertThat(StatementGoldens.difference(expected, "text", 80, null, out.text())).isNull();
        assertThat(StatementGoldens.difference(expected, "html", 100, null, out.html())).isNull();
        assertThat(out.result().statements()).isEqualTo(50);
        assertThat(out.result().listed()).isEqualTo(262);
        assertThat(out.result().textRecords()).isEqualTo(Long.parseLong(expected.getProperty("text.records")));
        assertThat(out.result().htmlRecords()).isEqualTo(Long.parseLong(expected.getProperty("html.records")));
    }

    /**
     * edge: BR-STMT-001 (a card without transactions), BR-STMT-003 (negative and zero amounts),
     * BR-STMT-005, BR-STMT-006 and BR-STMT-008 (double spaces, blank parts, {@code *} in names and
     * addresses, 25-character names), BR-STMT-009 (cards outside the cross-reference before,
     * between and after), BR-STMT-010 and NUM-STMT-006 ({@code *} in IDs and descriptions), DEF-STMT-002
     * (descriptions over 49 characters, cut), DEF-STMT-003 (HTML lines cut at a double space or
     * {@code *}), DEF-STMT-004 (a 10-digit balance), DEF-STMT-005 (two cards of one account, two
     * statements), DEF-STMT-007 and NUM-STMT-007 (HTML and Latin-1 characters as stored).
     */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"BR-STMT-005", "BR-STMT-006", "BR-STMT-008", "BR-STMT-009", "DEF-STMT-002", "DEF-STMT-003",
            "DEF-STMT-004", "DEF-STMT-005", "DEF-STMT-007"})
    void theEdgeCaseIsByteIdenticalToTheHarness(FidelityMode mode) {
        assertHarnessOutput("edge", StatementGoldens.synthetic("edge").render());
    }

    /** NUM-STMT-004 (totals beyond nine integer digits, truncated after each addition) and the largest balances. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-004"})
    void theTotalOverflowCaseIsByteIdenticalToTheHarness(FidelityMode mode) {
        assertHarnessOutput("total-overflow", StatementGoldens.synthetic("total-overflow").render());
    }

    /** The table limit exactly: 51 cards of 10 transactions, and a 52nd cross-reference card that takes no slot. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void theMaxTableCaseIsByteIdenticalToTheHarness(FidelityMode mode) {
        assertHarnessOutput("max-table", StatementGoldens.synthetic("max-table").render());
    }

    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "BR-STMT-009"})
    void theOrphanOnlyCaseIsByteIdenticalToTheHarness(FidelityMode mode) {
        assertHarnessOutput("orphan-only", StatementGoldens.synthetic("orphan-only").render());
    }

    /**
     * DEF-STMT-010 (fixed in both modes): where the program abends on an empty work file, the
     * renderer writes the statements without transactions. They are the program's own output for
     * a work file whose only card is outside the cross-reference.
     */
    @Test
    void anEmptyWorkFileGivesTheStatementsOfCardsWithoutTransactions() {
        Properties expected = StatementGoldens.expected("orphan-only");
        Inputs inputs = StatementGoldens.synthetic("orphan-only");
        Rendered out = inputs.withWorkFile(List.of()).render();

        assertThat(StatementGoldens.difference(expected, "text", 80,
                StatementGoldens.expectedOutput("orphan-only", "STMTFILE"), out.text())).isNull();
        assertThat(StatementGoldens.difference(expected, "html", 100,
                StatementGoldens.expectedOutput("orphan-only", "HTMLFILE"), out.html())).isNull();
        assertThat(inputs.render().result().unlisted()).isEqualTo(1);
        assertThat(out.result().unlisted()).isZero();
    }

    /**
     * DEF-STMT-001 legacy: the program overlays its storage (over-cards: a segmentation fault after
     * garbage lines; over-transactions: the 11th transaction lost and a line made of the next
     * card's slot; over-transactions-last: all 11 printed). The renderer refuses all three before
     * it writes a byte.
     */
    @ParameterizedTest
    @CsvSource({"over-cards, 52, 1, LEGACY", "over-transactions, 2, 11, LEGACY", "over-transactions-last, 2, 11, LEGACY"})
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void aWorkFileBeyondTheTableIsRefusedBeforeAnyByte(String name, int cards, int most, FidelityMode mode) {
        assertThat(StatementGoldens.expected(name).getProperty("expected")).isEqualTo("STATEMENT_TABLE_LIMIT");
        Inputs in = StatementGoldens.synthetic(name);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        ByteArrayOutputStream html = new ByteArrayOutputStream();

        assertThatThrownBy(() -> LegacyStatementRenderer.render(in.xref(), in.customers(), in.accounts(),
                in.workFile(), text, html))
                .isInstanceOfSatisfying(LegacyStatementException.class, e -> {
                    assertThat(e.code()).isEqualTo(LegacyStatementException.STATEMENT_TABLE_LIMIT);
                    assertThat(e.cards()).isEqualTo(cards);
                    assertThat(e.mostTransactionsOnACard()).isEqualTo(most);
                    assertThat(e.getMessage()).startsWith("STATEMENT_TABLE_LIMIT: ").doesNotContainPattern("\\d{11}");
                });
        assertThat(text.size()).isZero();
        assertThat(html.size()).isZero();
    }
}
