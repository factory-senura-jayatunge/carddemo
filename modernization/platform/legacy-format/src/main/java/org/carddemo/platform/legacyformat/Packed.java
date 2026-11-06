package org.carddemo.platform.legacyformat;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Packed decimal (COMP-3): two digits per byte, the last half-byte is the sign
 * ({@code C} positive, {@code D} negative, {@code F} unsigned), and a leading
 * zero half-byte pads an even digit count. Length is {@code digits / 2 + 1}.
 */
public final class Packed {

    private Packed() {
    }

    public static int length(Pic pic) {
        return pic.digits() / 2 + 1;
    }

    /** Strict encode: the value must fit the picture. */
    public static byte[] encode(BigDecimal value, Pic pic) {
        if (!LegacyNumbers.fits(value, pic)) {
            throw new LegacyFormatException("value does not fit picture " + pic);
        }
        return encodeMove(value, pic);
    }

    /**
     * COBOL MOVE into the item: high-order digits and extra decimals dropped, an
     * unsigned item takes the magnitude, and a negative source keeps sign
     * {@code D} even when no digit is left.
     */
    public static byte[] encodeMove(BigDecimal value, Pic pic) {
        char[] digits = LegacyNumbers.digitsOf(value, pic);
        int sign = !pic.signed() ? 0xF : value.signum() < 0 ? 0xD : 0xC;
        int len = length(pic);
        int[] nibbles = new int[len * 2];
        int start = nibbles.length - 1 - digits.length;
        for (int i = 0; i < digits.length; i++) {
            nibbles[start + i] = digits[i] - '0';
        }
        nibbles[nibbles.length - 1] = sign;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
        }
        return out;
    }

    public static BigDecimal decode(byte[] data, Pic pic) {
        return decode(data, 0, pic);
    }

    /**
     * Strict decode: every digit half-byte 0-9, the pad half-byte of an even
     * digit count 0, sign {@code A}, {@code C}, {@code E} or {@code F} positive and
     * {@code B} or {@code D} negative; an unsigned picture accepts only
     * positive signs.
     */
    public static BigDecimal decode(byte[] data, int offset, Pic pic) {
        int len = length(pic);
        if (offset < 0 || offset + len > data.length) {
            throw new LegacyFormatException("packed field at offset " + offset + " runs past the record");
        }
        int nibbleCount = len * 2;
        int firstDigit = nibbleCount - 1 - pic.digits();
        BigInteger value = BigInteger.ZERO;
        long acc = 0;
        int accDigits = 0;
        for (int i = 0; i < nibbleCount - 1; i++) {
            int b = data[offset + i / 2] & 0xFF;
            int nib = (i % 2 == 0) ? b >>> 4 : b & 0x0F;
            if (i < firstDigit) {
                if (nib != 0) {
                    throw new LegacyFormatException("packed field at offset " + offset + " has a non-zero pad");
                }
                continue;
            }
            if (nib > 9) {
                throw new LegacyFormatException("packed field at offset " + offset + " has an invalid digit");
            }
            acc = acc * 10 + nib;
            if (++accDigits == 18) {
                value = value.multiply(BigInteger.TEN.pow(18)).add(BigInteger.valueOf(acc));
                acc = 0;
                accDigits = 0;
            }
        }
        value = value.multiply(BigInteger.TEN.pow(accDigits)).add(BigInteger.valueOf(acc));
        int sign = data[offset + len - 1] & 0x0F;
        boolean negative;
        switch (sign) {
            case 0xA, 0xC, 0xE, 0xF -> negative = false;
            case 0xB, 0xD -> negative = true;
            default -> throw new LegacyFormatException("packed field at offset " + offset + " has an invalid sign");
        }
        if (negative && !pic.signed()) {
            throw new LegacyFormatException("packed field at offset " + offset + " is negative for unsigned " + pic);
        }
        return new BigDecimal(negative ? value.negate() : value, pic.scale());
    }
}
