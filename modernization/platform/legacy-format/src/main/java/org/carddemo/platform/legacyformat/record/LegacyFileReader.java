package org.carddemo.platform.legacyformat.record;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.carddemo.platform.legacyformat.LegacyFormatException;

/**
 * Reads a line-sequential copy of a fixed-length file, as the sample files in
 * {@code app/data/ASCII} are stored: one record per line, LF or CRLF (both
 * occur in one file), a final line ending optional. A line shorter than the
 * record is padded with spaces (the cross-reference sample has 36-byte lines
 * for a 50-byte record); a longer line or an empty line is an error.
 */
public final class LegacyFileReader {

    private LegacyFileReader() {
    }

    /** One record and where it came from. */
    public record SourceLine(int lineNumber, byte[] record, int originalLength, boolean crlf) {

        public boolean padded() {
            return originalLength < record.length;
        }

        public FixedRecord view(RecordLayout layout) {
            return FixedRecord.of(layout, record);
        }

        @Override
        public String toString() {
            return "SourceLine[" + lineNumber + "]";
        }
    }

    public static List<SourceLine> read(Path file, int recordLength) {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, recordLength, file.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<SourceLine> read(InputStream in, int recordLength, String sourceName) throws IOException {
        byte[] all = in.readAllBytes();
        List<SourceLine> out = new ArrayList<>();
        int start = 0;
        int lineNo = 0;
        while (start < all.length) {
            int nl = indexOf(all, (byte) '\n', start);
            int stop = nl < 0 ? all.length : nl;
            boolean crlf = stop > start && all[stop - 1] == '\r';
            int contentEnd = crlf ? stop - 1 : stop;
            lineNo++;
            int len = contentEnd - start;
            if (len == 0) {
                throw new LegacyFormatException(sourceName + " line " + lineNo + " is empty");
            }
            if (len > recordLength) {
                throw new LegacyFormatException(sourceName + " line " + lineNo + " has " + len
                        + " bytes, the record is " + recordLength);
            }
            byte[] rec = new byte[recordLength];
            System.arraycopy(all, start, rec, 0, len);
            Arrays.fill(rec, len, recordLength, (byte) ' ');
            out.add(new SourceLine(lineNo, rec, len, crlf));
            start = nl < 0 ? all.length : nl + 1;
        }
        return out;
    }

    private static int indexOf(byte[] a, byte b, int from) {
        for (int i = from; i < a.length; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }
}
