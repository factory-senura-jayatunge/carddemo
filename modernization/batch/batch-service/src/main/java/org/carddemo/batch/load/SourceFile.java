package org.carddemo.batch.load;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
        return parse(name, layout, readBytes(dir, name));
    }

    /** Every byte of the file, read once. */
    static byte[] readBytes(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new DataLoadException(name + " not found in carddemo.data-load.source-dir");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DataLoadException(name + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The records of bytes already read, so that a step can hash exactly what it loads. */
    static SourceFile parse(String name, RecordLayout layout, byte[] bytes) {
        try {
            return new SourceFile(name, layout,
                    LegacyFileReader.read(new ByteArrayInputStream(bytes), layout.length(), name));
        } catch (LegacyFormatException e) {
            // Its messages name the line, field and position, never the content (LegacyFormatException).
            throw new DataLoadException(e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
     * The message names the two lines, not the keys: account and customer IDs
     * and card numbers are personal data (D-NFR-04).
     */
    void requireAscendingKeys(int keyLength) {
        SourceLine previous = null;
        String previousKey = null;
        for (SourceLine l : lines) {
            String key = key(l, keyLength);
            if (previous != null) {
                int c = key.compareTo(previousKey);
                if (c == 0) {
                    throw new DataLoadException(where(l) + ": duplicate key, the same as line " + previous.lineNumber());
                }
                if (c < 0) {
                    throw new DataLoadException(where(l) + ": key out of order, below that of line "
                            + previous.lineNumber());
                }
            }
            previous = l;
            previousKey = key;
        }
    }

    /**
     * FILLERs that no column keeps must hold their constant, and zoned amounts
     * must not be a negative zero, or the re-export would not equal the source.
     *
     * <p>The unnamed FILLERs at the 1-based positions {@code dropped} are the only
     * exception: the load stores nothing of them and the export writes their constant,
     * so they may hold anything. The lines where one of them does not hold its
     * constant are returned, in file order; the content itself is never reported.
     */
    List<Integer> requireRepresentable(Set<Integer> dropped) {
        for (int position : dropped) {
            if (layout.fields().stream().noneMatch(f -> unnamedFiller(f) && f.position() == position)) {
                throw new IllegalArgumentException(name + " has no unnamed FILLER at position " + position);
            }
        }
        List<Integer> droppedLines = new ArrayList<>();
        for (SourceLine l : lines) {
            FixedRecord r = l.view(layout);
            boolean counted = false;
            for (FieldSpec f : layout.fields()) {
                if (unnamedFiller(f) && !r.raw(f).equals(String.valueOf(f.fill()).repeat(f.length()))) {
                    if (!dropped.contains(f.position())) {
                        throw new DataLoadException(where(l) + ": FILLER at position " + f.position()
                                + " does not hold its constant");
                    }
                    if (!counted) {
                        droppedLines.add(l.lineNumber());
                        counted = true;
                    }
                }
                if (f.type() == FieldType.ZONED && r.isNegativeZero(f.name())) {
                    throw new DataLoadException(where(l) + ": " + f.name() + " is a negative zero");
                }
            }
        }
        return droppedLines;
    }

    private static boolean unnamedFiller(FieldSpec f) {
        return f.type() == FieldType.FILLER && f.name().startsWith("FILLER-");
    }

    String where(SourceLine l) {
        return name + " line " + l.lineNumber();
    }

    /**
     * Runs a per-line conversion, adding the file and line to a format error. Another
     * exception keeps only its class: its message may quote the record.
     */
    <T> T convert(SourceLine l, Function<byte[], T> parse) {
        try {
            return parse.apply(l.record());
        } catch (LegacyFormatException e) {
            throw new DataLoadException(where(l) + ": " + e.getMessage());
        } catch (RuntimeException e) {
            throw new DataLoadException(where(l) + ": the record cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }
}
