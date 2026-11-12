package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.statement.LegacyStatementRenderer.Result;
import org.carddemo.batch.statement.LegacyStatementRenderer.WorkRecord;
import org.carddemo.batch.statement.StatementGoldens.Inputs;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The renderer's checks and counts, on the golden inputs; the bytes are the golden test's. */
class LegacyStatementRendererTest {

    private final ByteArrayOutputStream text = new ByteArrayOutputStream();
    private final ByteArrayOutputStream html = new ByteArrayOutputStream();

    private Result render(Inputs in) throws Exception {
        return LegacyStatementRenderer.render(in.xref(), in.customers(), in.accounts(), in.workFile(), text, html);
    }

    @Test
    void theTableLimitsAreTheProgramsOccursClauses() {
        assertThat(LegacyStatementRenderer.MAX_CARDS).isEqualTo(51);
        assertThat(LegacyStatementRenderer.MAX_TRANSACTIONS_PER_CARD).isEqualTo(10);
    }

    /** BR-STMT-009: a card outside the cross-reference still takes a slot of the table. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005", "BR-STMT-009"})
    void aCardOutsideTheCrossReferenceCountsAgainstTheTable(FidelityMode mode) {
        Inputs full = StatementGoldens.synthetic("max-table");
        List<WorkRecord> work = new ArrayList<>(full.workFile());
        WorkRecord last = work.getLast();
        String next = String.format("%016d", new BigInteger(last.cardNum()).add(BigInteger.valueOf(1000)));
        work.add(new WorkRecord(next, last.tranId(), "", BigDecimal.ONE));

        assertThatThrownBy(() -> render(full.withWorkFile(work)))
                .isInstanceOfSatisfying(LegacyStatementException.class, e -> {
                    assertThat(e.code()).isEqualTo(LegacyStatementException.STATEMENT_TABLE_LIMIT);
                    assertThat(e.cards()).isEqualTo(52);
                    assertThat(e.mostTransactionsOnACard()).isEqualTo(10);
                });
        assertThat(text.size() + html.size()).isZero();
    }

    /** The limit is on the work file's cards: a 52nd cross-reference card without transactions fits. */
    @ParameterizedTest
    @EnumSource(value = FidelityMode.class, names = "LEGACY")
    @SpecItem({"DEF-STMT-005", "DEF-STMT-001", "DEF-JSTMT-005"})
    void crossReferenceCardsWithoutTransactionsTakeNoSlot(FidelityMode mode) throws Exception {
        Inputs full = StatementGoldens.synthetic("max-table");
        assertThat(full.xref()).hasSize(52);

        Result r = render(full);

        assertThat(r.statements()).isEqualTo(52);
        assertThat(r.listed()).isEqualTo(510);
        assertThat(r.unlisted()).isZero();
    }

    @Test
    void aMissingCustomerIsRefusedBeforeAnyByte() {
        Inputs edge = StatementGoldens.synthetic("edge");
        CardXrefRecord third = edge.xref().get(2);
        Map<String, CustomerRecord> customers = new LinkedHashMap<>(edge.customers());
        customers.remove(third.custId());

        assertThatThrownBy(() -> LegacyStatementRenderer.render(edge.xref(), customers, edge.accounts(),
                edge.workFile(), text, html))
                .isInstanceOfSatisfying(LegacyStatementException.class, e -> {
                    assertThat(e.code()).isEqualTo(LegacyStatementException.STATEMENT_CUSTOMER_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo(
                            "STATEMENT_CUSTOMER_NOT_FOUND: cross-reference record 3 has no customer (BR-STMT-004)");
                });
        assertThat(text.size() + html.size()).isZero();
    }

    @Test
    void aMissingAccountIsRefusedBeforeAnyByte() {
        Inputs edge = StatementGoldens.synthetic("edge");
        CardXrefRecord last = edge.xref().getLast();
        Map<String, AccountRecord> accounts = new LinkedHashMap<>(edge.accounts());
        accounts.remove(last.acctId());

        assertThatThrownBy(() -> LegacyStatementRenderer.render(edge.xref(), edge.customers(), accounts,
                edge.workFile(), text, html))
                .isInstanceOfSatisfying(LegacyStatementException.class, e -> assertThat(e.getMessage()).isEqualTo(
                        "STATEMENT_ACCOUNT_NOT_FOUND: cross-reference record 5 has no account (BR-STMT-004)"));
        assertThat(text.size() + html.size()).isZero();
    }

    /** TRXFL is a KSDS read in key order; a work file out of that order is a caller error. */
    @Test
    void aWorkFileOutOfKeyOrderIsRejectedBeforeAnyByte() {
        Inputs edge = StatementGoldens.synthetic("edge");
        List<WorkRecord> reversed = new ArrayList<>(edge.workFile()).reversed();
        List<WorkRecord> duplicated = new ArrayList<>(edge.workFile());
        duplicated.add(1, duplicated.getFirst());

        assertThatThrownBy(() -> render(edge.withWorkFile(reversed)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("work file record 2");
        assertThatThrownBy(() -> render(edge.withWorkFile(duplicated)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("work file record 2");
        assertThat(text.size() + html.size()).isZero();
    }

    /** BR-STMT-001, BR-STMT-002, BR-STMT-009 on the edge case: 5 statements, 4 transactions on no statement. */
    @Test
    void theResultCountsStatementsListedAndUnlistedTransactions() throws Exception {
        Result r = render(StatementGoldens.synthetic("edge"));

        assertThat(r).isEqualTo(new Result(5, 9, 4, 5 * 19 + 9, 5 * 64 + 11 * 9));
        assertThat(text.size()).isEqualTo(80 * (5 * 19 + 9));
        assertThat(html.size()).isEqualTo(100 * (5 * 64 + 11 * 9));
    }

    @Test
    void aWorkRecordMustHaveTheLegacyFieldSizes() {
        String sixteen = "0".repeat(16);
        assertThatThrownBy(() -> new WorkRecord("0".repeat(15), sixteen, "", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkRecord(sixteen, sixteen, "x".repeat(101), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkRecord(sixteen, sixteen, "", new BigDecimal("1000000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
        String card = "1234".repeat(4);
        assertThat(new WorkRecord(card, sixteen, "", new BigDecimal("-999999999.99")).toString())
                .doesNotContain(card.substring(0, 12));
    }
}
