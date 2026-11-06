package org.carddemo.platform.legacyformat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numeric part of a COBOL PICTURE: total digits, digits after the implied
 * decimal point, and whether the item is signed. Only {@code S}, {@code 9},
 * {@code 9(n)} and {@code V} are accepted, which covers every DISPLAY, COMP-3
 * and COMP numeric item in the CardDemo record layouts.
 */
public record Pic(int digits, int scale, boolean signed) {

    private static final Pattern NINES = Pattern.compile("9\\((\\d+)\\)");
    private static final Pattern VALID = Pattern.compile("S?9+(V9+)?|S?V9+");

    public Pic {
        if (digits < 1 || digits > 31 || scale < 0 || scale > digits) {
            throw new IllegalArgumentException("invalid numeric picture: digits " + digits + ", scale " + scale);
        }
    }

    public static Pic parse(String picture) {
        String upper = picture.trim().toUpperCase();
        Matcher m = NINES.matcher(upper);
        StringBuilder expanded = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(expanded, "9".repeat(Integer.parseInt(m.group(1))));
        }
        m.appendTail(expanded);
        String p = expanded.toString();
        if (!VALID.matcher(p).matches()) {
            throw new IllegalArgumentException("unsupported numeric picture: " + picture);
        }
        boolean signed = p.startsWith("S");
        String body = signed ? p.substring(1) : p;
        int v = body.indexOf('V');
        int scale = v < 0 ? 0 : body.length() - v - 1;
        int digits = body.replace("V", "").length();
        return new Pic(digits, scale, signed);
    }

    public static Pic signed(int integerDigits, int scale) {
        return new Pic(integerDigits + scale, scale, true);
    }

    public static Pic unsigned(int integerDigits, int scale) {
        return new Pic(integerDigits + scale, scale, false);
    }

    public int integerDigits() {
        return digits - scale;
    }

    @Override
    public String toString() {
        return (signed ? "S" : "") + "9(" + integerDigits() + ")" + (scale > 0 ? "V9(" + scale + ")" : "");
    }
}
