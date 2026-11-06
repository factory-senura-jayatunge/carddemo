package org.carddemo.platform.legacyformat.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.platform.legacyformat.Binary;
import org.carddemo.platform.legacyformat.Packed;
import org.carddemo.platform.legacyformat.Pic;

/** A fixed-length record layout: the fields of one copybook, in order, without gaps. */
public final class RecordLayout {

    private final String name;
    private final int length;
    private final List<FieldSpec> fields;
    private final Map<String, FieldSpec> byName;

    private RecordLayout(String name, int length, List<FieldSpec> fields) {
        this.name = name;
        this.length = length;
        this.fields = List.copyOf(fields);
        Map<String, FieldSpec> m = new LinkedHashMap<>();
        for (FieldSpec f : fields) {
            if (m.put(f.name(), f) != null) {
                throw new IllegalArgumentException(name + ": duplicate field " + f.name());
            }
        }
        this.byName = Collections.unmodifiableMap(m);
    }

    public static Builder builder(String name, int length) {
        return new Builder(name, length);
    }

    public String name() {
        return name;
    }

    public int length() {
        return length;
    }

    /** Every field including FILLERs, in record order. */
    public List<FieldSpec> fields() {
        return fields;
    }

    public FieldSpec field(String fieldName) {
        FieldSpec f = byName.get(fieldName);
        if (f == null) {
            throw new IllegalArgumentException(name + " has no field " + fieldName);
        }
        return f;
    }

    public boolean has(String fieldName) {
        return byName.containsKey(fieldName);
    }

    @Override
    public String toString() {
        return name + "(" + length + ")";
    }

    public static final class Builder {
        private final String name;
        private final int length;
        private final List<FieldSpec> fields = new ArrayList<>();
        private int offset;
        private int fillers;

        private Builder(String name, int length) {
            this.name = name;
            this.length = length;
        }

        public Builder text(String field, int len) {
            return add(field, len, FieldType.TEXT, null, ' ');
        }

        public Builder digits(String field, int len) {
            return add(field, len, FieldType.DIGITS, Pic.unsigned(len, 0), ' ');
        }

        public Builder zoned(String field, String picture) {
            Pic pic = Pic.parse(picture);
            return add(field, pic.digits(), FieldType.ZONED, pic, ' ');
        }

        public Builder packed(String field, String picture) {
            Pic pic = Pic.parse(picture);
            return add(field, Packed.length(pic), FieldType.PACKED, pic, '\0');
        }

        public Builder binary(String field, String picture) {
            Pic pic = Pic.parse(picture);
            return add(field, Binary.length(pic), FieldType.BINARY, pic, '\0');
        }

        /** A FILLER of spaces. */
        public Builder filler(int len) {
            return filler(len, ' ');
        }

        /** A FILLER whose legacy records hold this character throughout. */
        public Builder filler(int len, char fill) {
            return add("FILLER-" + (++fillers), len, FieldType.FILLER, null, fill);
        }

        /** A named FILLER that a target column keeps (for example {@code legacy_filler}). */
        public Builder namedFiller(String field, int len, char fill) {
            return add(field, len, FieldType.FILLER, null, fill);
        }

        private Builder add(String field, int len, FieldType type, Pic pic, char fill) {
            if (len <= 0) {
                throw new IllegalArgumentException(name + "." + field + ": length " + len);
            }
            fields.add(new FieldSpec(field, offset, len, type, pic, fill));
            offset += len;
            return this;
        }

        public RecordLayout build() {
            if (offset != length) {
                throw new IllegalStateException(name + ": fields cover " + offset + " bytes, record is " + length);
            }
            return new RecordLayout(name, length, fields);
        }
    }
}
