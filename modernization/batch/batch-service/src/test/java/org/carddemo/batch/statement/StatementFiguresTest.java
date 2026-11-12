package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * One account's figures (batch.md, "Dates, balances and the reconciled total", totals ruled
 * 2026-11-11): each row is in exactly one of payments and credits, purchases and other debits, or
 * interest; interest is signed; {@code N} is their sum; the opening balance is the previous closing
 * balance or {@code C - N}; {@code A = C - (O + N)}.
 */
class StatementFiguresTest {

    static final LocalDate DATE = LocalDate.of(2022, 7, 6);
    static final BigDecimal FLOOR = new BigDecimal("25.00");
    static final BigDecimal PERCENT = new BigDecimal("1.00");

    static StatementFigures.Line line(String amount, boolean interest, LocalDate procDate) {
        return new StatementFigures.Line(new BigDecimal(amount), interest, procDate);
    }

    static StatementFigures of(String closing, Optional<StatementFigures.Previous> previous,
            List<StatementFigures.Line> lines) {
        return StatementFigures.of(DATE, new BigDecimal(closing), new BigDecimal("5000.00"), previous, lines, FLOOR,
                PERCENT);
    }

    @Test
    void theTotalsAreMutuallyExclusiveAndInterestIsSigned() {
        StatementFigures f = of("257.00", Optional.empty(), List.of(
                line("100.00", false, DATE.minusDays(3)),
                line("-50.00", false, DATE.minusDays(2)),
                line("10.00", true, DATE),
                // A negative interest row (DEF-INTC-012 legacy) lowers interest and is not a credit.
                line("-3.00", true, DATE)));

        assertThat(f.purchasesAndDebits()).isEqualByComparingTo("100.00");
        assertThat(f.paymentsAndCredits()).isEqualByComparingTo("-50.00");
        assertThat(f.interest()).isEqualByComparingTo("7.00");
        assertThat(f.net()).isEqualByComparingTo("57.00")
                .isEqualByComparingTo(f.paymentsAndCredits().add(f.purchasesAndDebits()).add(f.interest()));
        assertThat(f.tranCount()).isEqualTo(4);
        assertThat(f.first()).isTrue();
        assertThat(f.opening()).isEqualByComparingTo("200.00");
        assertThat(f.adjustment()).isEqualByComparingTo("0.00");
        assertThat(f.adjusted()).isFalse();
        assertThat(f.periodStart()).isEqualTo(DATE.minusDays(3));
        assertThat(f.periodEnd()).isEqualTo(DATE);
        // max(25.00, 2.57 + 7.00)
        assertThat(f.minimumPayment()).isEqualByComparingTo("25.00");
    }

    @Test
    void aPreviousStatementGivesTheOpeningBalanceAndTheAdjustmentReconciles() {
        StatementFigures f = of("220.00", Optional.of(new StatementFigures.Previous(new BigDecimal("150.00"),
                LocalDate.of(2022, 6, 30))), List.of(line("57.00", false, DATE.minusDays(1))));

        assertThat(f.first()).isFalse();
        assertThat(f.periodStart()).isEqualTo(LocalDate.of(2022, 7, 1));
        assertThat(f.opening()).isEqualByComparingTo("150.00");
        assertThat(f.adjustment()).isEqualByComparingTo("13.00");
        assertThat(f.adjusted()).isTrue();
        assertThat(f.opening().add(f.net()).add(f.adjustment())).isEqualByComparingTo(f.closing());
    }

    @Test
    void theMinimumPaymentUsesOnlyPositiveInterest() {
        StatementFigures negative = of("3000.00", Optional.empty(), List.of(line("-12.00", true, DATE)));
        assertThat(negative.interest()).isEqualByComparingTo("-12.00");
        assertThat(negative.minimumPayment()).isEqualByComparingTo("30.00");

        StatementFigures positive = of("3000.00", Optional.empty(), List.of(line("37.50", true, DATE)));
        assertThat(positive.minimumPayment()).isEqualByComparingTo("67.50");
    }

    @Test
    void aFirstStatementWithoutRowsOrWithOnlyLaterOnlineRowsStartsOnTheStatementDate() {
        StatementFigures empty = of("80.00", Optional.empty(), List.of());
        assertThat(empty.periodStart()).isEqualTo(DATE);
        assertThat(empty.net()).isEqualByComparingTo("0.00");
        assertThat(empty.opening()).isEqualByComparingTo("80.00");

        StatementFigures later = of("80.00", Optional.empty(), List.of(line("-20.00", false, DATE.plusDays(1))));
        assertThat(later.periodStart()).isEqualTo(DATE);
        assertThat(later.opening()).isEqualByComparingTo("100.00");
    }
}
