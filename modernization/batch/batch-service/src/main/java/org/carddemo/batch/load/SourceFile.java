package org.carddemo.batch.load;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.FieldType;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;

/** One sample file read through its layout, with the checks every load step makes. */
final class SourceFile {

    private final String name;
    private final RecordLayout layout;
    private final List<SourceLine> lines;

    private SourceFile(String name, RecordLayout layout, List<SourceLine> lines) {
        this.name = name;
        this.layout = layout;
        this.lines = lines;
    }

    static SourceFile read(Path dir, String name, RecordLayout layout) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new DataLoadException(name + " not found in " + dir);
        }
        try {
            return new SourceFile(name, layout, LegacyFileReader.read(file, layout.length()));
        } catch (LegacyFormatException e) {
            throw new DataLoadException(e.getMessage());
        }
    }

    String name() {
        return name;
    }

    List<SourceLine> lines() {
        return lines;
    }

    String key(SourceLine line, int keyLength) {
        return new String(line.record(), 0, keyLength, StandardCharsets.ISO_8859_1);
    }

    /**
     * Keys must be strictly ascending in byte order, as a KSDS REPRO requires
     * (VAL-JACCT-001 and its siblings): a duplicate or out-of-order key fails.
     */
    void requireAscendingKeys(int keyLength, UnaryOperator<String> display) {
        String previous = null;
        for (SourceLine l : lines) {
            String key = key(l, keyLength);
            if (previous != null) {
                int c = key.compareTo(previous);
                if (c == 0) {
                    throw new DataLoadException(where(l) + ": duplicate key " + display.apply(key));
                }
                if (c < 0) {
                    throw new DataLoadException(where(l) + ": key " + display.apply(key)
                            + " is out of order (below " + display.apply(previous) + ")");
                }
            }
            previous = key;
        }
    }

    /**
     * FILLERs that no column keeps must hold their constant, and zoned amounts
     * must not be a negative zero, or the re-export would not equal the source.
     */
    void requireRepresentable() {
        for (SourceLine l : lines) {
            FixedRecord r = l.view(layout);
            for (FieldSpec f : layout.fields()) {
                if (f.type() == FieldType.FILLER && f.name().startsWith("FILLER-")
                        && !r.raw(f).equals(String.valueOf(f.fill()).repeat(f.length()))) {
                    throw new DataLoadException(where(l) + ": FILLER at position " + f.position()
                            + " does not hold its constant");
                }
                if (f.type() == FieldType.ZONED && r.isNegativeZero(f.name())) {
                    throw new DataLoadException(where(l) + ": " + f.name() + " is a negative zero");
                }
            }
        }
    }

    String where(SourceLine l) {
        return name + " line " + l.lineNumber();
    }

    /** Runs a per-line conversion, adding the file and line to a format error. */
    <T> T convert(SourceLine l, java.util.function.Function<byte[], T> parse) {
        try {
            return parse.apply(l.record());
        } catch (LegacyFormatException e) {
            throw new DataLoadException(where(l) + ": " + e.getMessage());
        }
    }
}
