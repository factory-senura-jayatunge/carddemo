package org.carddemo.platform.validation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The CSUTLDTC date check: whether a 10-character date is a calendar date in a picture made of
 * {@code YYYY}, {@code MM} and {@code DD} plus separators, with the severity and LE message number
 * CEEDAYS would give. The rules are those of the CEEDAYS stand-in the reference COBOL runs with
 * ({@code tools/shims/CEEDAYS.cbl}): separators are not compared, every position the picture gives
 * a part must hold digits, the month is checked before the range and the range before the day.
 * Message 2513 (before 1582-10-15) is reported as CEEDAYS reports it; whether a caller accepts such a
 * date is the caller's rule (DEF-DTCK-004). Stateless and thread-safe.
 */
public final class LegacyDateCheck {

    /** The picture transaction add (COTRN02C) and report request (CORPT00C) use. */
    public static final String DASHED = "YYYY-MM-DD";
    /** The picture account update (COACTUPC, through CSUTLDPY) uses. */
    public static final String COMPACT = "YYYYMMDD";

    private static final int FIELD = 10;

    private LegacyDateCheck() {
    }

    /**
     * Checks {@code date} against {@code picture}. Both are taken as a COBOL {@code PIC X(10)} holds
     * them after a MOVE: longer text is cut to 10 characters, shorter text and {@code null} are padded
     * with spaces (BR-DTCK-001).
     */
    public static DateCheckResult check(String date, String picture) {
        String d = field(date);
        String p = field(picture);
        String pic = asciiUpperCase(p);
        int yPos = -1;
        int mPos = -1;
        int dPos = -1;
        int message = 0;
        for (int i = 0; i < FIELD; i++) {
            if (yPos < 0 && pic.startsWith("YYYY", i)) {
                yPos = i;
                i += 3;
            } else if (mPos < 0 && pic.startsWith("MM", i)) {
                mPos = i;
                i += 1;
            } else if (dPos < 0 && pic.startsWith("DD", i)) {
                dPos = i;
                i += 1;
            } else if (isAsciiLetter(pic.charAt(i))) {
                message = DateCheckResult.BAD_PICTURE;
            }
        }
        if (yPos < 0 || mPos < 0 || dPos < 0) {
            message = DateCheckResult.BAD_PICTURE;
        }
        if (message != 0) {
            return failed(message, d, p);
        }
        if (d.chars().allMatch(c -> c == ' ')) {
            return failed(DateCheckResult.INSUFFICIENT, d, p);
        }
        String yyyy = part(d, yPos, 4);
        String mm = part(d, mPos, 2);
        String dd = part(d, dPos, 2);
        if (!digits(yyyy) || !digits(mm) || !digits(dd)) {
            return failed(DateCheckResult.NONNUMERIC, d, p);
        }
        int year = Integer.parseInt(yyyy);
        int month = Integer.parseInt(mm);
        int day = Integer.parseInt(dd);
        if (month < 1 || month > 12) {
            return failed(DateCheckResult.INVALID_MONTH, d, p);
        }
        if (year < 1582 || (year == 1582 && (month < 10 || (month == 10 && day < 15)))) {
            return failed(DateCheckResult.UNSUPPORTED_RANGE, d, p);
        }
        if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
            return failed(DateCheckResult.BAD_DATE_VALUE, d, p);
        }
        return new DateCheckResult(0, 0, "Date is valid", d, p, Optional.of(LocalDate.of(year, month, day)));
    }

    private static DateCheckResult failed(int message, String date, String picture) {
        return new DateCheckResult(3, message, verdict(message), date, picture, Optional.empty());
    }

    /** BR-DTCK-002. */
    static String verdict(int message) {
        return switch (message) {
            case DateCheckResult.INSUFFICIENT -> "Insufficient";
            case DateCheckResult.BAD_DATE_VALUE -> "Datevalue error";
            case DateCheckResult.INVALID_ERA -> "Invalid Era";
            case DateCheckResult.UNSUPPORTED_RANGE -> "Unsupp. Range";
            case DateCheckResult.INVALID_MONTH -> "Invalid month";
            case DateCheckResult.BAD_PICTURE -> "Bad Pic String";
            case DateCheckResult.NONNUMERIC -> "Nonnumeric data";
            case DateCheckResult.YEAR_IN_ERA_ZERO -> "YearInEra is 0";
            default -> "Date is invalid";
        };
    }

    static String field(String text) {
        if (text == null) {
            return " ".repeat(FIELD);
        }
        return DateCheckResult.pad(text, FIELD);
    }

    /** The picture positions past the 10th read as spaces, as in CEEDAYS's 256-byte work area. */
    private static String part(String date, int from, int length) {
        return DateCheckResult.pad(date.substring(Math.min(from, FIELD)), length);
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static String asciiUpperCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return b.toString();
    }
}
