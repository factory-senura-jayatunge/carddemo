package org.carddemo.platform.legacyformat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Numeric storage rules of the legacy programs.
 *
 * <p>No CardDemo program checks size errors, so a result that does not fit its
 * receiving item silently loses its high-order digits, and extra decimals are
 * truncated, never rounded (NUM-BILL-002, NUM-POST-003). {@link #truncate} is
 * that rule; it is meant for code paths whose fidelity switch is {@code legacy}.
 * Fixed mode should call {@link #fits} and reject the value instead.
 */
public final class LegacyNumbers {

    private LegacyNumbers() {
    }

    /** The value as a receiving item with this many integer digits and decimals holds it. */
    public static BigDecimal truncate(BigDecimal value, int integerDigits, int scale) {
        BigDecimal scaled = value.setScale(scale, RoundingMode.DOWN);
        BigInteger unscaled = scaled.unscaledValue();
        BigInteger limit = BigInteger.TEN.pow(integerDigits + scale);
        BigInteger kept = unscaled.abs().mod(limit);
        return new BigDecimal(unscaled.signum() < 0 ? kept.negate() : kept, scale);
    }

    /** {@link #truncate(BigDecimal, int, int)} keeping the value's own decimals. */
    public static BigDecimal truncate(BigDecimal value, int integerDigits) {
        return truncate(value, integerDigits, Math.max(value.scale(), 0));
    }

    /**
     * MOVE of a signed {@code S9(intDigits)V9(scale)} value to the edited picture
     * {@code +9(intDigits).9(scale)}, e.g. {@code +0000000194.00} for (194.00, 10, 2).
     * The sign is {@code +} for zero.
     */
    public static String editSigned(BigDecimal value, int integerDigits, int scale) {
        String picture = "+9(" + integerDigits + ")" + (scale > 0 ? ".9(" + scale + ")" : "");
        return NumericEdit.of(picture).format(value, Pic.signed(integerDigits, scale));
    }

    public static BigDecimal truncate(BigDecimal value, Pic pic) {
        BigDecimal t = truncate(value, pic.integerDigits(), pic.scale());
        return pic.signed() ? t : t.abs();
    }

    /** True when the value is stored unchanged by an item with this picture. */
    public static boolean fits(BigDecimal value, Pic pic) {
        if (!pic.signed() && value.signum() < 0) {
            return false;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > pic.scale()) {
            return false;
        }
        return value.abs().setScale(pic.scale(), RoundingMode.UNNECESSARY).unscaledValue()
                .compareTo(BigInteger.TEN.pow(pic.digits())) < 0;
    }

    /**
     * The digits an item holds after a MOVE: {@code pic.digits()} characters,
     * high-order digits and extra decimals dropped.
     */
    static char[] digitsOf(BigDecimal value, Pic pic) {
        BigInteger mag = value.abs().setScale(pic.scale(), RoundingMode.DOWN).unscaledValue()
                .mod(BigInteger.TEN.pow(pic.digits()));
        String s = mag.toString();
        char[] out = new char[pic.digits()];
        int pad = out.length - s.length();
        for (int i = 0; i < out.length; i++) {
            out[i] = i < pad ? '0' : s.charAt(i - pad);
        }
        return out;
    }
}
