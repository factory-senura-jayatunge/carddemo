package org.carddemo.batch.pauth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FieldSpec;
import org.carddemo.platform.legacyformat.record.HdUnloadReader;

/**
 * The sample pending-authorization unload with one field of one detail replaced, for tests of
 * values the sample does not hold. It walks the {@code RECFM=VB} records itself (descriptor
 * length, segment name at offset 10, data at offset 39), so it does not depend on the reader
 * under test.
 */
final class PauthUnload {

    static final Path REPO = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    static final Path SAMPLE = REPO.resolve(
            "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat");

    private static final int NAME_OFFSET = 10;
    private static final int DATA_OFFSET = 39;

    /** The changed file and the 1-based record number of the changed detail, as the reader counts. */
    record Patched(byte[] bytes, int recordNumber) {
    }

    private PauthUnload() {
    }

    static byte[] sample() {
        try {
            return Files.readAllBytes(SAMPLE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The first detail whose summary is loaded (not the all-space root) gets {@code value} in {@code field}. */
    static Patched withFirstDetailField(String field, byte[] value) {
        byte[] file = sample();
        FieldSpec target = Layouts.PENDING_AUTH_DETAIL.field(field);
        if (value.length != target.length()) {
            throw new IllegalArgumentException(field + " has " + target.length() + " bytes");
        }
        FieldSpec acct = Layouts.PENDING_AUTH_SUMMARY.field("PA-ACCT-ID");
        boolean loadedRoot = false;
        int pos = 0;
        for (int number = 1; pos < file.length; number++) {
            int length = ((file[pos] & 0xFF) << 8) | (file[pos + 1] & 0xFF);
            String name = number == 1 ? "" : new String(file, pos + NAME_OFFSET, 8, HdUnloadReader.EBCDIC);
            if (name.equals(PendingAuthorizationRows.ROOT)) {
                loadedRoot = false;
                for (int i = 0; i < acct.length(); i++) {
                    loadedRoot |= (file[pos + DATA_OFFSET + acct.offset() + i] & 0xFF) != 0x40;
                }
            } else if (name.equals(PendingAuthorizationRows.CHILD) && loadedRoot) {
                System.arraycopy(value, 0, file, pos + DATA_OFFSET + target.offset(), value.length);
                return new Patched(file, number);
            }
            pos += length;
        }
        throw new IllegalStateException("the sample has no detail under a loaded summary");
    }

    static Path write(Path dir, Patched patched) {
        Path file = dir.resolve(SAMPLE.getFileName());
        try {
            return Files.write(file, patched.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
