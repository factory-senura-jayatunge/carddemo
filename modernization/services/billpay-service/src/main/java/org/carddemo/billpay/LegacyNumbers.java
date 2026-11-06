package org.carddemo.billpay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The two COBOL number rules Bill Pay shows. Stand-in for {@code platform/legacy-format}'s
 * {@code LegacyNumbers} until that module is on the integration branch; same signatures.
 */
final class LegacyNumbers {

    private LegacyNumbers() {
    }

    /**
     * A COBOL {@code +9(intDigits).9(scale)} edit: sign, zero-padded integer part cut to its
     * low-order digits, point, decimals cut (not rounded). {@code (194.00, 10, 2)} gives
     * {@code +0000000194.00}.
     */
    static String editSigned(BigDecimal value, int intDigits, int scale) {
        BigDecimal v = truncate(value, intDigits).setScale(scale, RoundingMode.DOWN);
        String digits = v.abs().movePointRight(scale).toBigInteger().toString();
        String padded = "0".repeat(Math.max(0, intDigits + scale - digits.length())) + digits;
        String sign = v.signum() < 0 ? "-" : "+";
        return sign + padded.substring(0, intDigits) + (scale > 0 ? "." + padded.substring(intDigits) : "");
    }

    /** Drops the integer digits above {@code intDigits}, as a COBOL MOVE to a shorter field does; keeps sign and scale. */
    static BigDecimal truncate(BigDecimal value, int intDigits) {
        BigDecimal modulus = BigDecimal.TEN.pow(intDigits);
        BigDecimal kept = value.abs().remainder(modulus);
        return (value.signum() < 0 ? kept.negate() : kept).setScale(value.scale(), RoundingMode.UNNECESSARY);
    }
}
