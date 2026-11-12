package org.carddemo.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import org.carddemo.platform.legacyformat.NumericEdit;
import org.carddemo.platform.legacyformat.Pic;

/** The account screens' number and date forms. */
public final class AccountFormats {

    /** The amount columns' legacy item, S9(10)V99. */
    private static final Pic AMOUNT = Pic.signed(10, 2);
    private static final NumericEdit LEGACY = NumericEdit.of("+ZZZ,ZZZ,ZZZ.99");
    /** One more digit group, so an amount of a billion or more keeps every digit (DEF-ACVW-005). */
    private static final NumericEdit WIDE = NumericEdit.of("+Z,ZZZ,ZZZ,ZZZ.99");
    private static final BigDecimal BILLION = new BigDecimal("1000000000");

    private AccountFormats() {
    }

    /** Decimal string, scale 2, {@code -} for negatives. */
    public static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).toPlainString();
    }

    /** NUM-ACVW-001: {@code +ZZZ,ZZZ,ZZZ.99}, or {@code +Z,ZZZ,ZZZ,ZZZ.99} at a billion or more. */
    public static String amountDisplay(BigDecimal value) {
        return (value.abs().compareTo(BILLION) < 0 ? LEGACY : WIDE).format(value, AMOUNT);
    }

    /** NUM-ACVW-002: {@code YYYY-MM-DD}; a date not stored is {@code ""}. */
    public static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    /** NUM-ACVW-003: nine digits as {@code nnn-nn-nnnn}; any other value as stored. */
    public static String ssnDisplay(String ssn) {
        if (ssn == null || ssn.length() != 9) {
            return ssn;
        }
        return ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
    }

    /**
     * The stored group without the blanks of its {@code NCHAR(10)} padding. Only U+0020 is removed:
     * a tab, U+0085 or U+00A0 at the end is part of the value and is shown as stored.
     */
    public static String groupId(String stored) {
        int end = stored.length();
        while (end > 0 && stored.charAt(end - 1) == ' ') {
            end--;
        }
        return stored.substring(0, end);
    }

    /** The legacy PIC 9(3) of the FICO score. */
    public static String fico(int score) {
        return String.format(Locale.ROOT, "%03d", score);
    }
}
