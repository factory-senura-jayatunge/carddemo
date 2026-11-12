package org.carddemo.platform.legacyformat.record;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.carddemo.platform.legacyformat.Binary;
import org.carddemo.platform.legacyformat.Latin1;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.Packed;
import org.carddemo.platform.legacyformat.Zoned;

/**
 * A read-only view of one fixed-length record through its layout. Bytes map to
 * characters one to one in a single-byte charset: ISO-8859-1 as the ASCII
 * sample files are written, or an EBCDIC code page for mainframe files. Packed
 * and binary fields are read from the bytes whatever the charset.
 * Errors name the layout, field and position, never the content.
 */
public final class FixedRecord {

    private final RecordLayout layout;
    private final byte[] data;
    private final Charset charset;

    private FixedRecord(RecordLayout layout, byte[] data, Charset charset) {
        this.layout = layout;
        this.data = data;
        this.charset = charset;
    }

    public static FixedRecord of(RecordLayout layout, byte[] data) {
        return of(layout, data, StandardCharsets.ISO_8859_1);
    }

    public static FixedRecord of(RecordLayout layout, byte[] data, Charset charset) {
        if (data.length != layout.length()) {
            throw new LegacyFormatException(layout.name() + ": record has " + data.length + " bytes, layout needs "
                    + layout.length());
        }
        if (charset.newEncoder().maxBytesPerChar() != 1.0f) {
            throw new IllegalArgumentException(charset + " is not a single-byte charset");
        }
        return new FixedRecord(layout, data.clone(), charset);
    }

    /** The record as text, one character per byte; a character above U+00FF is an error. */
    public static FixedRecord of(RecordLayout layout, String text) {
        return of(layout, Latin1.encode(text, layout.name()));
    }

    public RecordLayout layout() {
        return layout;
    }

    public Charset charset() {
        return charset;
    }

    public byte[] bytes() {
        return data.clone();
    }

    /** The field's characters exactly as stored. */
    public String raw(String field) {
        FieldSpec f = layout.field(field);
        return raw(f);
    }

    public String raw(FieldSpec f) {
        return new String(data, f.offset(), f.length(), charset);
    }

    public byte[] rawBytes(String field) {
        FieldSpec f = layout.field(field);
        return Arrays.copyOfRange(data, f.offset(), f.end());
    }

    /** True when every byte of the field is {@code value}, for example a key of all {@code X'40'}. */
    public boolean allBytes(String field, int value) {
        FieldSpec f = layout.field(field);
        for (int i = f.offset(); i < f.end(); i++) {
            if ((data[i] & 0xFF) != (value & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** A text field without its trailing spaces (leading spaces are kept). */
    public String text(String field) {
        return stripTrailing(raw(field));
    }

    /** A {@code 9(n)} identifier: must be all digits. */
    public String digits(String field) {
        FieldSpec f = layout.field(field);
        String s = raw(f);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw error(f, "is not all digits");
            }
        }
        return s;
    }

    public boolean isAllDigits(String field) {
        String s = raw(field);
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** The value of a zoned, packed or binary field, or of a {@code 9(n)} field read as a number. */
    public BigDecimal decimal(String field) {
        FieldSpec f = layout.field(field);
        try {
            return switch (f.type()) {
                case ZONED, DIGITS -> Zoned.decode(raw(f), f.pic());
                case PACKED -> Packed.decode(data, f.offset(), f.pic());
                case BINARY -> Binary.decode(data, f.offset(), f.pic());
                default -> throw error(f, "is not numeric");
            };
        } catch (LegacyFormatException e) {
            throw error(f, e.getMessage());
        }
    }

    public boolean isNegativeZero(String field) {
        FieldSpec f = layout.field(field);
        return f.type() == FieldType.ZONED && Zoned.isNegativeZero(raw(f));
    }

    private LegacyFormatException error(FieldSpec f, String what) {
        return new LegacyFormatException(layout.name() + "." + f.name() + " (position " + f.position() + ", length "
                + f.length() + ") " + what);
    }

    static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /** Never shows the content: records carry card numbers and personal data. */
    @Override
    public String toString() {
        return "FixedRecord[" + layout + "]";
    }
}
