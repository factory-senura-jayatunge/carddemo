package org.carddemo.platform.legacyformat.record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.carddemo.platform.legacyformat.LegacyFormatException;

/**
 * Reads an IMS HD unload file ({@code RECFM=VB}), such as the pending-authorization sample
 * {@code AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat} (ENT-AUTH-HD-UNLOAD). Each record starts with a
 * 4-byte record descriptor: the record length including the descriptor, then two zero bytes.
 * The first and the last record are the unload utility's header and trailer, 88 bytes each,
 * and are skipped. Every other record is one segment: the segment length at offset 8 (2 bytes,
 * big-endian), the segment name at offset 10 (8 EBCDIC characters), the segment data from
 * offset 39, and one {@code X'00'} byte after the data. The data is therefore not the last
 * bytes of the record. A record of any other shape, or a segment without a layout, fails the read. Errors
 * give record numbers and lengths, never content.
 */
public final class HdUnloadReader {

    /** The EBCDIC code page of the CardDemo mainframe files. */
    public static final Charset EBCDIC = Charset.forName("IBM037");

    static final int DESCRIPTOR = 4;
    static final int HEADER_LENGTH = 88;
    static final int SEGMENT_LENGTH_OFFSET = 8;
    static final int NAME_OFFSET = 10;
    static final int NAME_LENGTH = 8;
    static final int DATA_OFFSET = 39;
    static final int AFTER_DATA = 1;

    private HdUnloadReader() {
    }

    /** One segment, with its 1-based record number in the file. */
    public record Segment(int recordNumber, String name, FixedRecord data) {

        @Override
        public String toString() {
            return "Segment[" + recordNumber + ", " + name + "]";
        }
    }

    /** A root segment and the segments the unload wrote after it, before the next root. */
    public record Root(Segment segment, List<Segment> children) {

        public Root {
            children = List.copyOf(children);
        }

        @Override
        public String toString() {
            return "Root[" + segment + ", " + children.size() + " children]";
        }
    }

    /** @param layouts the data layout of each segment name the file may hold */
    public static List<Segment> read(Path file, Map<String, RecordLayout> layouts) {
        try {
            return read(Files.readAllBytes(file), file.getFileName().toString(), layouts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Segment> read(byte[] file, String sourceName, Map<String, RecordLayout> layouts) {
        List<int[]> records = new ArrayList<>();
        int pos = 0;
        while (pos < file.length) {
            int number = records.size() + 1;
            if (file.length - pos < DESCRIPTOR) {
                throw new LegacyFormatException(sourceName + " record " + number + ": the file ends inside the record descriptor");
            }
            int length = ((file[pos] & 0xFF) << 8) | (file[pos + 1] & 0xFF);
            if (file[pos + 2] != 0 || file[pos + 3] != 0) {
                throw new LegacyFormatException(sourceName + " record " + number + ": spanned or invalid record descriptor");
            }
            if (length <= DESCRIPTOR) {
                throw new LegacyFormatException(sourceName + " record " + number + ": record length " + length);
            }
            if (pos + length > file.length) {
                throw new LegacyFormatException(sourceName + " record " + number + ": " + length
                        + " bytes, the file ends after " + (file.length - pos));
            }
            records.add(new int[] {pos, length});
            pos += length;
        }
        if (records.size() < 2 || records.get(0)[1] != HEADER_LENGTH || records.get(records.size() - 1)[1] != HEADER_LENGTH) {
            throw new LegacyFormatException(sourceName + ": no " + HEADER_LENGTH + "-byte unload header and trailer");
        }
        List<Segment> out = new ArrayList<>(records.size() - 2);
        for (int i = 1; i < records.size() - 1; i++) {
            int start = records.get(i)[0];
            int length = records.get(i)[1];
            int number = i + 1;
            if (length < DATA_OFFSET + AFTER_DATA) {
                throw new LegacyFormatException(sourceName + " record " + number + ": " + length
                        + " bytes is too short for a segment");
            }
            String name = new String(file, start + NAME_OFFSET, NAME_LENGTH, EBCDIC);
            RecordLayout layout = layouts.get(name);
            if (layout == null) {
                throw new LegacyFormatException(sourceName + " record " + number + ": unexpected segment name");
            }
            int segmentLength = ((file[start + SEGMENT_LENGTH_OFFSET] & 0xFF) << 8)
                    | (file[start + SEGMENT_LENGTH_OFFSET + 1] & 0xFF);
            if (segmentLength != layout.length() || length != DATA_OFFSET + segmentLength + AFTER_DATA) {
                throw new LegacyFormatException(sourceName + " record " + number + ": " + name + " of " + length
                        + " bytes with segment length " + segmentLength + ", the layout needs "
                        + (DATA_OFFSET + layout.length() + AFTER_DATA) + " and " + layout.length());
            }
            if (file[start + length - 1] != 0) {
                throw new LegacyFormatException(sourceName + " record " + number + ": the byte after the segment data is not X'00'");
            }
            byte[] data = Arrays.copyOfRange(file, start + DATA_OFFSET, start + DATA_OFFSET + segmentLength);
            out.add(new Segment(number, name, FixedRecord.of(layout, data, EBCDIC)));
        }
        return out;
    }

    /**
     * Groups the segments under their roots: the unload writes each root followed by its
     * children, so a segment belongs to the root before it. A child before the first root fails.
     */
    public static List<Root> roots(List<Segment> segments, String rootName) {
        List<Root> out = new ArrayList<>();
        Segment root = null;
        List<Segment> children = new ArrayList<>();
        for (Segment s : segments) {
            if (s.name().equals(rootName)) {
                if (root != null) {
                    out.add(new Root(root, children));
                }
                root = s;
                children = new ArrayList<>();
            } else if (root == null) {
                throw new LegacyFormatException("record " + s.recordNumber() + ": " + s.name() + " before the first "
                        + rootName);
            } else {
                children.add(s);
            }
        }
        if (root != null) {
            out.add(new Root(root, children));
        }
        return out;
    }
}
