package org.carddemo.platform.legacyformat.record;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.carddemo.platform.legacyformat.Binary;
import org.carddemo.platform.legacyformat.Latin1;
import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.Packed;
import org.carddemo.platform.legacyformat.Zoned;

/**
 * Builds a fixed-length record field by field. Every field starts as its
 * layout fill (spaces, a FILLER's constant, binary zeros for packed and binary
 * fields). Setters are strict: text that is too long or has a character above
 * U+00FF, an identifier that is not all digits, or a number that does not fit
 * is an error, never truncated or replaced.
 */
public final class RecordBuilder {

    private final RecordLayout layout;
    private final byte[] data;

    private RecordBuilder(RecordLayout layout) {
        this.layout = layout;
        this.data = new byte[layout.length()];
        for (FieldSpec f : layout.fields()) {
            Arrays.fill(data, f.offset(), f.end(), (byte) f.fill());
        }
    }

    public static RecordBuilder of(RecordLayout layout) {
        return new RecordBuilder(layout);
    }

    /** Text, padded with spaces on the right; {@code null} leaves the field at its fill. */
    public RecordBuilder text(String field, String value) {
        FieldSpec f = layout.field(field);
        if (value == null) {
            return this;
        }
        if (value.length() > f.length()) {
            throw error(f, "value is longer than the field");
        }
        put(f, value + " ".repeat(f.length() - value.length()));
        return this;
    }

    /** A {@code 9(n)} identifier given as text: exactly {@code n} digits. */
    public RecordBuilder digits(String field, String value) {
        FieldSpec f = layout.field(field);
        if (value == null) {
            return this;
        }
        if (value.length() != f.length() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw error(f, "value is not " + f.length() + " digits");
        }
        put(f, value);
        return this;
    }

    /** A {@code 9(n)} field from a number, with leading zeros. */
    public RecordBuilder digits(String field, long value) {
        FieldSpec f = layout.field(field);
        String s = Long.toString(value);
        if (value < 0 || s.length() > f.length()) {
            throw error(f, "value does not fit");
        }
        put(f, "0".repeat(f.length() - s.length()) + s);
        return this;
    }

    /** A zoned, packed or binary field; zero is stored positive. */
    public RecordBuilder decimal(String field, BigDecimal value) {
        FieldSpec f = layout.field(field);
        if (value == null) {
            return this;
        }
        try {
            switch (f.type()) {
                case ZONED, DIGITS -> put(f, Zoned.encode(value, f.pic()));
                case PACKED -> System.arraycopy(Packed.encode(value, f.pic()), 0, data, f.offset(), f.length());
                case BINARY -> System.arraycopy(Binary.encode(value, f.pic()), 0, data, f.offset(), f.length());
                default -> throw error(f, "is not numeric");
            }
        } catch (LegacyFormatException e) {
            throw error(f, e.getMessage());
        }
        return this;
    }

    /** The field's exact characters (used for FILLER content kept from the source). */
    public RecordBuilder raw(String field, String value) {
        FieldSpec f = layout.field(field);
        if (value == null) {
            return this;
        }
        if (value.length() != f.length()) {
            throw error(f, "raw value has the wrong length");
        }
        put(f, value);
        return this;
    }

    public byte[] build() {
        return data.clone();
    }

    public String buildString() {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private void put(FieldSpec f, String s) {
        byte[] b;
        try {
            b = Latin1.encode(s, "value");
        } catch (LegacyFormatException e) {
            throw error(f, e.getMessage());
        }
        System.arraycopy(b, 0, data, f.offset(), f.length());
    }

    private LegacyFormatException error(FieldSpec f, String what) {
        return new LegacyFormatException(layout.name() + "." + f.name() + " (position " + f.position() + ", length "
                + f.length() + "): " + what);
    }
}
