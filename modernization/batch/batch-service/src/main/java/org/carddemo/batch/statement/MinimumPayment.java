package org.carddemo.batch.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The minimum payment (batch.md, "Minimum payment", formula ruled 2026-11-11): with {@code C} the
 * closing balance and {@code I} the period's interest, {@code 0.00} when {@code C <= 0}, otherwise
 * {@code min(C, max(F, round(C * P / 100) + max(0, I)))}, rounded half-up to cents, in decimal
 * arithmetic.
 */
final class MinimumPayment {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private MinimumPayment() {
    }

    static BigDecimal of(BigDecimal closing, BigDecimal periodInterest, BigDecimal floor, BigDecimal percent) {
        if (closing.signum() <= 0) {
            return ZERO;
        }
        BigDecimal share = closing.multiply(percent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        BigDecimal due = floor.max(share.add(periodInterest.max(BigDecimal.ZERO)));
        return closing.min(due).setScale(2, RoundingMode.UNNECESSARY);
    }
}
