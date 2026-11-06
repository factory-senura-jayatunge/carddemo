package org.carddemo.platform.legacyformat.record;

import org.carddemo.platform.legacyformat.Pic;

/**
 * One field of a fixed layout. {@code offset} is 0-based; the copybook
 * position is {@code offset + 1}. {@code fill} is what an exporter writes when
 * nothing is set: spaces for text, or the constant a FILLER holds in every
 * legacy record (for example 22 zeros in the category balance file).
 */
public record FieldSpec(String name, int offset, int length, FieldType type, Pic pic, char fill) {

    public int position() {
        return offset + 1;
    }

    public int end() {
        return offset + length;
    }

    public boolean numeric() {
        return type == FieldType.ZONED || type == FieldType.PACKED || type == FieldType.BINARY;
    }
}
