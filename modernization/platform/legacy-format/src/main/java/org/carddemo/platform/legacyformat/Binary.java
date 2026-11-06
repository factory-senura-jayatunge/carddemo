package org.carddemo.platform.legacyformat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Binary (COMP) integers, big-endian, holding the value times 10^scale:
 * 2 bytes for 1-4 digits, 4 for 5-9, 8 for 10-18; signed items are two's
 * complement.
 *
 * <p>{@link #encodeMove} is what GnuCOBOL 3.2 stores on a MOVE with the build's
 * flags: decimals beyond the scale are dropped, an unsigned item takes the
 * magnitude, and the integer is reduced modulo 2^(8 x length), not to the
 * picture's digit count (a MOVE of 1234567890 into {@code 9(9) COMP} stores
 * 1234567890).
 */
public final class Binary {

    private Binary() {
    }

    public static int length(Pic pic) {
        if (pic.digits() <= 4) {
            return 2;
        }
        if (pic.digits() <= 9) {
            return 4;
        }
        if (pic.digits() <= 18) {
            return 8;
        }
        throw new LegacyFormatException("binary picture " + pic + " has more than 18 digits");
    }

    /** Strict encode: the value must fit the picture. */
    public static byte[] encode(BigDecimal value, Pic pic) {
        if (!LegacyNumbers.fits(value, pic)) {
            throw new LegacyFormatException("value does not fit picture " + pic);
        }
        return encodeMove(value, pic);
    }

    public static byte[] encodeMove(BigDecimal value, Pic pic) {
        int len = length(pic);
        BigInteger units = value.setScale(pic.scale(), RoundingMode.DOWN).unscaledValue();
        if (!pic.signed()) {
            units = units.abs();
        }
        BigInteger modulus = BigInteger.ONE.shiftLeft(8 * len);
        BigInteger stored = units.mod(modulus);
        byte[] raw = stored.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }

    public static BigDecimal decode(byte[] data, Pic pic) {
        return decode(data, 0, pic);
    }

    public static BigDecimal decode(byte[] data, int offset, Pic pic) {
        int len = length(pic);
        if (offset < 0 || offset + len > data.length) {
            throw new LegacyFormatException("binary field at offset " + offset + " runs past the record");
        }
        byte[] slice = new byte[len];
        System.arraycopy(data, offset, slice, 0, len);
        BigInteger v = pic.signed() ? new BigInteger(slice) : new BigInteger(1, slice);
        return new BigDecimal(v, pic.scale());
    }
}
