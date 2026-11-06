package org.carddemo.platform.legacyformat;

/** The layouts found in the 26-character timestamp fields. */
public enum TimestampLayout {
    /** {@code YYYY-MM-DD-HH.MM.SS.hh0000}: POSTTRAN and INTCALC. */
    DB2,
    /** {@code YYYY-MM-DD HH:MM:SS.ffffff}: the daily input file, and bill payment with a zero fraction. */
    ISO_MICROS,
    /** {@code YYYY-MM-DD} and 16 spaces: transaction add. */
    DATE_ONLY,
    /** All spaces, as {@code DALYTRAN-PROC-TS} in the sample input. */
    BLANK,
    /** Anything else. */
    UNKNOWN
}
