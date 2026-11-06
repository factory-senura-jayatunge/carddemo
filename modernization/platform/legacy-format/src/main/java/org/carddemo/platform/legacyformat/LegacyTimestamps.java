package org.carddemo.platform.legacyformat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * The 26-character timestamp layouts the legacy writers put into
 * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} (ENT-TRANSACTION, Q-BILL-006),
 * plus the export header timestamp. Each writer keeps its own layout in legacy
 * mode, so these are formatters per writer, not one canonical format.
 */
public final class LegacyTimestamps {

    /** Width of every {@code X(26)} timestamp field. */
    public static final int WIDTH = 26;

    // STRICT: the default resolver would read 2022-02-30 as 2022-02-28.
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DB2_PREFIX = DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO_PREFIX = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private LegacyTimestamps() {
    }

    /**
     * POSTTRAN and INTCALC ({@code Z-GET-DB2-FORMAT-TIMESTAMP}):
     * {@code YYYY-MM-DD-HH.MM.SS.hh0000}, hundredths of a second then four zeros.
     */
    public static String posting(LocalDateTime t) {
        return DB2_PREFIX.format(t) + "." + twoDigits(t.getNano() / 10_000_000) + "0000";
    }

    /** Bill payment (CICS FORMATTIME, NUM-BILL-003): {@code YYYY-MM-DD HH:MM:SS.000000}. */
    public static String online(LocalDateTime t) {
        return ISO_PREFIX.format(t) + ".000000";
    }

    /** The daily input layout: {@code YYYY-MM-DD HH:MM:SS.ffffff} (microseconds). */
    public static String isoMicros(LocalDateTime t) {
        return ISO_PREFIX.format(t) + "." + String.format("%06d", t.getNano() / 1_000);
    }

    /** Transaction add (COTRN02C): the typed date, then 16 spaces. */
    public static String dateOnly(LocalDate d) {
        return DATE.format(d) + " ".repeat(16);
    }

    /**
     * CBEXPORT's {@code EXPORT-TIMESTAMP}: {@code YYYY-MM-DD HH:MM:SS} and the
     * literal {@code .00} (the clock's hundredths are dropped), followed by four
     * bytes the program never sets (X'00' in the GnuCOBOL build, NUM-EXPT-001).
     */
    public static String exportHeader(LocalDateTime t) {
        return ISO_PREFIX.format(t) + ".00\0\0\0\0";
    }

    /** Which writer's layout a stored timestamp has. */
    public static TimestampLayout classify(String ts) {
        if (ts == null || ts.length() != WIDTH) {
            return TimestampLayout.UNKNOWN;
        }
        if (ts.isBlank()) {
            return TimestampLayout.BLANK;
        }
        if (ts.substring(10).isBlank() && parseDate(ts.substring(0, 10)).isPresent()) {
            return TimestampLayout.DATE_ONLY;
        }
        if (ts.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000") && parse(ts).isPresent()) {
            return TimestampLayout.DB2;
        }
        if (ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}") && parse(ts).isPresent()) {
            return TimestampLayout.ISO_MICROS;
        }
        return TimestampLayout.UNKNOWN;
    }

    /** The moment a stored timestamp names, for any known layout; a date-only value is midnight. */
    public static Optional<LocalDateTime> parse(String ts) {
        if (ts == null || ts.length() != WIDTH) {
            return Optional.empty();
        }
        try {
            if (ts.substring(10).isBlank()) {
                return parseDate(ts.substring(0, 10)).map(LocalDate::atStartOfDay);
            }
            String body = ts.substring(0, 19);
            String fraction = ts.substring(20);
            if (ts.charAt(19) != '.' || !fraction.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            LocalDateTime base;
            if (ts.charAt(10) == '-') {
                base = LocalDateTime.parse(body, DB2_PREFIX);
            } else if (ts.charAt(10) == ' ') {
                base = LocalDateTime.parse(body, ISO_PREFIX);
            } else {
                return Optional.empty();
            }
            return Optional.of(base.withNano(Integer.parseInt(fraction) * 1_000));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** A strict {@code YYYY-MM-DD} calendar date. */
    public static Optional<LocalDate> parseDate(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text, DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String formatDate(LocalDate d) {
        return DATE.format(d);
    }

    private static String twoDigits(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }
}
