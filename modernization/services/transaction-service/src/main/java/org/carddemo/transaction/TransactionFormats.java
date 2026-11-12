package org.carddemo.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.carddemo.platform.legacyformat.LegacyNumbers;
import org.carddemo.platform.legacyformat.LegacyTimestamps;

/** The display forms of the list and the view (COTRN00C, COTRN01C). */
final class TransactionFormats {

    /** {@code WS-TRAN-AMT PIC +99999999.99}: eight integer digits. */
    static final int AMOUNT_DIGITS = 8;
    /** The list row's {@code TDESC}: the first 26 characters of the description. */
    static final int LIST_DESCRIPTION = 26;

    private static final BigDecimal WIDE = new BigDecimal("100000000");
    private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private TransactionFormats() {
    }

    /** The decimal string of the API: scale 2, {@code -} for negatives. */
    static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * {@code +99999999.99} (NUM-TRNL-001, NUM-TRNV-001). The column holds nine integer digits, which
     * the legacy MOVE cut to eight; from 100,000,000.00 the picture has nine (DEF-TRNL-002,
     * DEF-TRNV-002), so no digit is lost.
     */
    static String amountDisplay(BigDecimal amount) {
        int digits = amount.abs().compareTo(WIDE) >= 0 ? AMOUNT_DIGITS + 1 : AMOUNT_DIGITS;
        return LegacyNumbers.editSigned(amount, digits, 2);
    }

    /** {@code mm/dd/yy} of the original timestamp (NUM-TRNL-002); none gives an empty text. */
    static String listDate(LocalDateTime origTs) {
        return origTs == null ? "" : LIST_DATE.format(origTs);
    }

    /** {@code YYYY-MM-DD HH:MM:SS.ffffff}, the stored 26-character layout (DEF-TRNV-006); none gives "". */
    static String timestamp(LocalDateTime ts) {
        return ts == null ? "" : LegacyTimestamps.isoMicros(ts);
    }

    /** The list's description: the first 26 characters, then trailing spaces removed. */
    static String listDescription(String description) {
        String text = description == null ? "" : description;
        return trimTrailing(text.length() > LIST_DESCRIPTION ? text.substring(0, LIST_DESCRIPTION) : text);
    }

    /**
     * Only trailing U+0020 is the field's pad. Other trailing white space, such as U+00A0, is data
     * the add stored as typed, so {@link String#stripTrailing()} would change it.
     */
    static String trimTrailing(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
