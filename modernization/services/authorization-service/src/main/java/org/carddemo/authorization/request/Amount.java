package org.carddemo.authorization.request;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * The request amount (BR-AUTH-002, fixed under D-32). Spaces at both ends are removed. The text is
 * valid when it is at most 13 characters, a plain positive decimal with at most ten integer digits
 * and two decimals, and greater than zero. An invalid amount is declined {@code 9000} and stored
 * as {@code 0.00}.
 */
public record Amount(boolean valid, BigDecimal value) {

    static final int MAX_LENGTH = 13;
    private static final Pattern FORM = Pattern.compile("\\+?([0-9]{1,10}(\\.[0-9]{0,2})?|\\.[0-9]{1,2})");
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    public static Amount parse(String raw) {
        String text = stripSpaces(raw);
        if (text.length() > MAX_LENGTH || !FORM.matcher(text).matches()) {
            return invalid();
        }
        String digits = text.startsWith("+") ? text.substring(1) : text;
        if (digits.endsWith(".")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        if (digits.startsWith(".")) {
            digits = "0" + digits;
        }
        BigDecimal value = new BigDecimal(digits).setScale(2, RoundingMode.UNNECESSARY);
        return value.signum() > 0 ? new Amount(true, value) : invalid();
    }

    static Amount invalid() {
        return new Amount(false, ZERO);
    }

    /** NUMVAL ignores leading and trailing spaces; only U+0020 is a space here. */
    private static String stripSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(start, end);
    }
}
