package org.carddemo.platform.validation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The amount format the account screens accept, in the form {@code NUMVAL-C} reads: surrounding
 * spaces, one sign (leading {@code +}/{@code -} before or after the currency sign, or trailing
 * {@code +}, {@code -}, {@code CR}, {@code DB} in either case), an optional leading {@code $}, digits with optional thousands commas, and at most one
 * decimal point. Unlike GnuCOBOL's {@code TEST-NUMVAL-C}, commas must group the integer digits by
 * three ({@code 1,00,0} and {@code 100,} are rejected), and a value with more integer digits or more
 * decimals than the field holds is rejected instead of cut (NUM-ACUP-101, DEF-ACUP-113 fixed).
 */
public final class LegacyAmount {

    private static final Pattern FORMAT = Pattern.compile(
            "^ *(?<lead>[+-])? *(?<cur>\\$)? *(?<lead2>[+-])? *(?<int>[0-9]{1,3}(?:,[0-9]{3})+|[0-9]*)"
                    + "(?:\\.(?<frac>[0-9]*))? *(?<trail>[+-]|CR|DB)? *$");

    private LegacyAmount() {
    }

    /**
     * The value of {@code text}, or empty when it is not a valid amount for a field with
     * {@code maxIntegerDigits} integer digits and {@code maxScale} decimals.
     */
    public static Optional<BigDecimal> parse(String text, int maxIntegerDigits, int maxScale) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text.toUpperCase(Locale.ROOT));
        if (!m.matches()) {
            return Optional.empty();
        }
        String lead = m.group("lead") != null ? m.group("lead") : m.group("lead2");
        String trail = m.group("trail");
        int signs = (m.group("lead") != null ? 1 : 0) + (m.group("lead2") != null ? 1 : 0) + (trail != null ? 1 : 0);
        if (signs > 1) {
            return Optional.empty();
        }
        String integer = m.group("int").replace(",", "");
        String fraction = m.group("frac") == null ? "" : m.group("frac");
        if (integer.isEmpty() && fraction.isEmpty()) {
            return Optional.empty();
        }
        String significant = integer.replaceFirst("^0+", "");
        if (significant.length() > maxIntegerDigits || fraction.length() > maxScale) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal((integer.isEmpty() ? "0" : integer) + (fraction.isEmpty() ? "" : "." + fraction));
        boolean negative = "-".equals(lead) || "-".equals(trail) || "CR".equals(trail) || "DB".equals(trail);
        return Optional.of(negative ? value.negate() : value);
    }
}
