package org.carddemo.platform.legacyformat.record;

/** How a field's bytes are interpreted. */
public enum FieldType {
    /** {@code X(n)}: characters, padded with spaces on the right. */
    TEXT,
    /** {@code 9(n)} used as an identifier or code: digit characters, kept as text. */
    DIGITS,
    /** Signed or unsigned zoned decimal ({@code S9(n)V9(m)} DISPLAY). */
    ZONED,
    /** {@code COMP-3}. */
    PACKED,
    /** {@code COMP}, big-endian. */
    BINARY,
    /** {@code FILLER}: not interpreted; carried as raw characters. */
    FILLER
}
