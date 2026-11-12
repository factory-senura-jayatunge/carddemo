package org.carddemo.batch.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One account's statement figures (batch.md, "Dates, balances and the reconciled total"; totals
 * ruled 2026-11-11). {@code N} is the net of the period's amounts. The three activity totals add up
 * to {@code N}: payments and credits are the negative amounts that are not interest, purchases and
 * other debits the positive ones that are not interest, and interest the signed sum of the
 * {@code INTEREST} rows (a negative legacy interest row stays in it). The opening balance is the
 * previous statement's closing balance, or {@code C - N} for a first statement, and the adjustment
 * is {@code A = C - (O + N)}.
 *
 * @param first whether the account has no statement in the previous run
 */
record StatementFigures(LocalDate periodStart, LocalDate periodEnd, BigDecimal opening, BigDecimal closing,
        BigDecimal net, BigDecimal paymentsAndCredits, BigDecimal purchasesAndDebits, BigDecimal interest,
        BigDecimal adjustment, BigDecimal minimumPayment, BigDecimal creditLimit, int tranCount, boolean first) {

    /** One row of the period: its amount, whether its origin is {@code INTEREST}, and its processing date. */
    record Line(BigDecimal amount, boolean interest, LocalDate procDate) {
        Line {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(procDate, "procDate");
        }
    }

    /** The account's statement in the previous run: its closing balance and the statement date. */
    record Previous(BigDecimal closing, LocalDate statementDate) {
        Previous {
            Objects.requireNonNull(closing, "closing");
            Objects.requireNonNull(statementDate, "statementDate");
        }
    }

    StatementFigures {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
    }

    /**
     * @param statementDate the cycle's business date, the period's last day
     * @param closing {@code C}, the captured {@code curr_bal}
     * @param creditLimit the captured credit limit
     * @param previous the account's statement in the previous run, empty for a first statement
     * @param lines the period's rows of the account
     */
    static StatementFigures of(LocalDate statementDate, BigDecimal closing, BigDecimal creditLimit,
            Optional<Previous> previous, List<Line> lines, BigDecimal floor, BigDecimal percent) {
        BigDecimal payments = BigDecimal.ZERO.setScale(2);
        BigDecimal purchases = BigDecimal.ZERO.setScale(2);
        BigDecimal interest = BigDecimal.ZERO.setScale(2);
        LocalDate earliest = null;
        for (Line line : lines) {
            if (line.interest()) {
                interest = interest.add(line.amount());
            } else if (line.amount().signum() < 0) {
                payments = payments.add(line.amount());
            } else {
                purchases = purchases.add(line.amount());
            }
            if (earliest == null || line.procDate().isBefore(earliest)) {
                earliest = line.procDate();
            }
        }
        BigDecimal net = payments.add(purchases).add(interest);
        LocalDate start;
        BigDecimal opening;
        if (previous.isPresent()) {
            start = previous.get().statementDate().plusDays(1);
            opening = previous.get().closing();
        } else {
            // An online row (Bill Pay) carries the wall clock's date, which can be after the cycle's
            // business date; the period never ends before it starts.
            start = earliest == null || earliest.isAfter(statementDate) ? statementDate : earliest;
            opening = closing.subtract(net);
        }
        if (start.isAfter(statementDate)) {
            throw new IllegalStateException("the period would start after the statement date");
        }
        BigDecimal adjustment = closing.subtract(opening.add(net));
        return new StatementFigures(start, statementDate, opening, closing, net, payments, purchases, interest,
                adjustment, MinimumPayment.of(closing, interest, floor, percent), creditLimit, lines.size(),
                previous.isEmpty());
    }

    boolean adjusted() {
        return adjustment.signum() != 0;
    }
}
