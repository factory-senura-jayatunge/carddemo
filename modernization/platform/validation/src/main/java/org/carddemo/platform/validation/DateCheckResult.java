package org.carddemo.platform.validation;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What CSUTLDTC returns for one date: the CEEDAYS condition severity and message number, the verdict
 * text, and the 10-character date and picture as given (BR-DTCK-002, BR-DTCK-003).
 *
 * @param severity      0 when the date is valid, else 3
 * @param messageNumber the LE message number (2507 to 2521), 0 when the date is valid
 * @param verdict       the 15-character verdict without its trailing spaces
 * @param testedDate    the 10 characters of the date that were tested
 * @param picture       the 10 characters of the picture that were used
 * @param value         the date, when it is valid (CSUTLDTC itself discards the day number, BR-DTCK-005)
 */
public record DateCheckResult(int severity, int messageNumber, String verdict, String testedDate, String picture,
                              Optional<LocalDate> value) {

    public static final int INSUFFICIENT = 2507;
    public static final int BAD_DATE_VALUE = 2508;
    public static final int INVALID_ERA = 2509;
    public static final int UNSUPPORTED_RANGE = 2513;
    public static final int INVALID_MONTH = 2517;
    public static final int BAD_PICTURE = 2518;
    public static final int NONNUMERIC = 2520;
    public static final int YEAR_IN_ERA_ZERO = 2521;

    public boolean valid() {
        return severity == 0;
    }

    /** The 80-character result line, as CSUTLDTC writes it to its third parameter (BR-DTCK-003). */
    public String line() {
        return "%04d".formatted(severity) + "Mesg Code: " + "%04d".formatted(messageNumber) + " "
                + pad(verdict, 15) + " " + "TstDate: " + testedDate + " " + "Mask used:" + picture + "    ";
    }

    /** The severity as the 4 characters callers read from positions 1-4. */
    public String severityCode() {
        return "%04d".formatted(severity);
    }

    /** The message number as the 4 characters callers read from positions 16-19. */
    public String messageCode() {
        return "%04d".formatted(messageNumber);
    }

    static String pad(String text, int length) {
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }
}
