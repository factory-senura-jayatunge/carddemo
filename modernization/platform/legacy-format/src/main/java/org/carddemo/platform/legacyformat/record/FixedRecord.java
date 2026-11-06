package org.carddemo.platform.legacyformat.record;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.carddemo.platform.legacyformat.Binary;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.Packed;
import org.carddemo.platform.legacyformat.Zoned;

/**
 * A read-only view of one fixed-length record through its layout. Bytes map to
 * characters one to one (ISO-8859-1), as the ASCII sample files are written.
 * Errors name the layout, field and position, never the content.
 */
public final class FixedRecord {

    private final RecordLayout layout;
    private final byte[] data;

    private FixedRecord(RecordLayout layout, byte[] data) {
        this.layout = layout;
        this.data = data;
    }

    public static FixedRecord of(RecordLayout layout, byte[] data) {
        if (data.length != layout.length()) {
            throw new LegacyFormatException(layout.name() + ": record has " + data.length + " bytes, layout needs "
                    + layout.length());
        }
        return new FixedRecord(layout, data.clone());
    }

    public static FixedRecord of(RecordLayout layout, String text) {
        return of(layout, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    public RecordLayout layout() {
        return layout;
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
        return new String(data, f.offset(), f.length(), StandardCharsets.ISO_8859_1);
    }

    public byte[] rawBytes(String field) {
        FieldSpec f = layout.field(field);
        return Arrays.copyOfRange(data, f.offset(), f.end());
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
