package org.carddemo.platform.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** COBOL MOVE into a signed numeric item: high-order integer digits are dropped, the sign kept (NUM-POST-003). */
final class Amounts {

    /** core.account amounts are DECIMAL(12,2), PIC S9(10)V99. */
    static final int ACCOUNT_DIGITS = 10;
    /** core.tcat_bal.balance is DECIMAL(11,2), PIC S9(09)V99. */
    static final int CATEGORY_DIGITS = 9;

    private Amounts() {
    }

    static BigDecimal truncate(BigDecimal value, int integerDigits) {
        int scale = Math.max(value.scale(), 0);
        BigDecimal scaled = value.setScale(scale, RoundingMode.DOWN);
        BigInteger unscaled = scaled.unscaledValue();
        BigInteger kept = unscaled.abs().mod(BigInteger.TEN.pow(integerDigits + scale));
        return new BigDecimal(unscaled.signum() < 0 ? kept.negate() : kept, scale);
    }
}
