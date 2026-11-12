package org.carddemo.authorization;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The legacy screens' rearrangements of stored text, shared by the summary list and the detail. */
public final class ScreenFormat {

    private ScreenFormat() {
    }

    /** NUM-AUSM-003, NUM-AUDT-002: {@code YYMMDD} as {@code MM/DD/YY}, rearranged without validation. */
    public static String date(String yymmdd) {
        String v = padded(yymmdd, 6);
        return v.substring(2, 4) + "/" + v.substring(4, 6) + "/" + v.substring(0, 2);
    }

    /** {@code HHMMSS} as {@code HH:MM:SS}. */
    public static String time(String hhmmss) {
        String v = padded(hhmmss, 6);
        return v.substring(0, 2) + ":" + v.substring(2, 4) + ":" + v.substring(4, 6);
    }

    /** NUM-AUDT-003: characters 1-2, {@code /}, characters 3-4. */
    public static String expiry(String fourChars) {
        String v = padded(fourChars, 4);
        return v.substring(0, 2) + "/" + v.substring(2, 4);
    }

    /** A stored DECIMAL(n,2) as its plain text with two decimals; display widths belong to the web. */
    public static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** The CHAR(n) as the COBOL field held it: padded with spaces. */
    private static String padded(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }
}
