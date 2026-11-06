package org.carddemo.platform.legacyformat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Zoned decimal (USAGE DISPLAY) numbers as the ASCII sample data and the
 * GnuCOBOL build ({@code -fsign=EBCDIC}) store them: one digit character per
 * digit, and for signed items the sign folded into the last character with the
 * mainframe overpunch: {@code {} and {@code A}-{@code I} positive 0-9,
 * {@code }} and {@code J}-{@code R} negative 0-9.
 *
 * <p>Three encoders differ only where the value does not fit or is zero:
 * {@link #encode} refuses anything that does not fit; {@link #encodeMove} is a
 * COBOL MOVE (truncates, and a negative source keeps its sign even when no
 * digit is left, so -0.009 becomes {@code 0000}}); {@link #encodeCompute} is the
 * result of COMPUTE or ADD (truncates; a result that is zero after dropping the
 * decimals is a positive zero, while one that is zero only after losing its
 * high-order digits keeps its sign). The three behaviours are what GnuCOBOL 3.2
 * produces, see the golden file of the tests.
 */
public final class Zoned {

    static final String POSITIVE = "{ABCDEFGHI";
    static final String NEGATIVE = "}JKLMNOPQR";

    private Zoned() {
    }

    /** Strict decode: digits only, sign by overpunch (or a plain last digit, read as positive). */
    public static BigDecimal decode(CharSequence field, Pic pic) {
        if (field.length() != pic.digits()) {
            throw new LegacyFormatException("zoned field has " + field.length() + " characters, picture "
                    + pic + " needs " + pic.digits());
        }
        int n = field.length();
        BigInteger value = BigInteger.ZERO;
        long acc = 0;
        int accDigits = 0;
        for (int i = 0; i < n - 1; i++) {
            char c = field.charAt(i);
            if (c < '0' || c > '9') {
                throw new LegacyFormatException("zoned field has a non-digit at offset " + i);
            }
            acc = acc * 10 + (c - '0');
            if (++accDigits == 18) {
                value = value.multiply(BigInteger.TEN.pow(18)).add(BigInteger.valueOf(acc));
                acc = 0;
                accDigits = 0;
            }
        }
        char last = field.charAt(n - 1);
        int digit;
        boolean negative = false;
        if (last >= '0' && last <= '9') {
            digit = last - '0';
        } else if (pic.signed() && POSITIVE.indexOf(last) >= 0) {
            digit = POSITIVE.indexOf(last);
        } else if (pic.signed() && NEGATIVE.indexOf(last) >= 0) {
            digit = NEGATIVE.indexOf(last);
            negative = true;
        } else {
            throw new LegacyFormatException("zoned field has an invalid " + (pic.signed() ? "sign" : "digit")
                    + " in its last position");
        }
        acc = acc * 10 + digit;
        accDigits++;
        value = value.multiply(BigInteger.TEN.pow(accDigits)).add(BigInteger.valueOf(acc));
        return new BigDecimal(negative ? value.negate() : value, pic.scale());
    }

    /** True for a field whose digits are all zero and whose sign is negative ({@code }} overpunch). */
    public static boolean isNegativeZero(CharSequence field) {
        int n = field.length();
        if (n == 0 || field.charAt(n - 1) != '}') {
            return false;
        }
        for (int i = 0; i < n - 1; i++) {
            if (field.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /** Strict encode: the value must fit the picture exactly. Zero is stored positive. */
    public static String encode(BigDecimal value, Pic pic) {
        if (!LegacyNumbers.fits(value, pic)) {
            throw new LegacyFormatException("value does not fit picture " + pic);
        }
        return build(LegacyNumbers.digitsOf(value, pic), pic.signed() && value.signum() < 0, pic.signed());
    }

    /** COBOL MOVE into the item. */
    public static String encodeMove(BigDecimal value, Pic pic) {
        return build(LegacyNumbers.digitsOf(value, pic), pic.signed() && value.signum() < 0, pic.signed());
    }

    /** COBOL COMPUTE or ADD storing into the item, without ON SIZE ERROR. */
    public static String encodeCompute(BigDecimal value, Pic pic) {
        boolean negative = pic.signed() && value.setScale(pic.scale(), RoundingMode.DOWN).signum() < 0;
        return build(LegacyNumbers.digitsOf(value, pic), negative, pic.signed());
    }

    /** Encode with an explicit sign, for re-exporting a negative zero read from a legacy file. */
    public static String encodeNegativeZero(Pic pic) {
        if (!pic.signed()) {
            throw new LegacyFormatException("unsigned picture " + pic + " has no negative zero");
        }
        return build(LegacyNumbers.digitsOf(BigDecimal.ZERO, pic), true, true);
    }

    private static String build(char[] digits, boolean negative, boolean signed) {
        if (signed) {
            int last = digits.length - 1;
            int d = digits[last] - '0';
            digits[last] = negative ? NEGATIVE.charAt(d) : POSITIVE.charAt(d);
        }
        return new String(digits);
    }
}
