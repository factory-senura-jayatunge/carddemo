package org.carddemo.platform.legacyformat.record;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes fixed-length records as a line-sequential file with LF endings. */
public final class LegacyFileWriter {

    private LegacyFileWriter() {
    }

    public static void write(Path file, List<byte[]> records) {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, records);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(OutputStream out, List<byte[]> records) throws IOException {
        for (byte[] r : records) {
            out.write(r);
            out.write('\n');
        }
    }
}
