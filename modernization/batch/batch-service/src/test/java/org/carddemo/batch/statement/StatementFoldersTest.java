package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.carddemo.batch.execution.FolderPublication;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.PublicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest format (contract section 4.8) and the read-back check of a statement folder. A
 * mismatch's detail holds counts only: no account ID (a statement file's name), entry name or path.
 */
class StatementFoldersTest {

    @TempDir
    Path root;

    Path folder;

    @BeforeEach
    void folder() throws IOException {
        folder = Files.createDirectory(root.resolve("12-1"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private void write(String name, String content) throws IOException {
        Path file = Files.createFile(folder.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content, StandardCharsets.US_ASCII);
    }

    private static SortedMap<String, String> legacy() {
        SortedMap<String, String> expected = new TreeMap<>();
        expected.put(StatementFolders.TEXT, null);
        expected.put(StatementFolders.HTML, null);
        return expected;
    }

    /** The two legacy files and the manifest of their hashes; returns the manifest's bytes. */
    private byte[] legacyFolder() throws IOException {
        write(StatementFolders.TEXT, "text\n");
        write(StatementFolders.HTML, "html\n");
        SortedMap<String, String> hashes = new TreeMap<>();
        hashes.put(StatementFolders.TEXT, OutputPublication.sha256("text\n".getBytes(StandardCharsets.US_ASCII)));
        hashes.put(StatementFolders.HTML, OutputPublication.sha256("html\n".getBytes(StandardCharsets.US_ASCII)));
        byte[] manifest = StatementFolders.manifest(hashes);
        write(FolderPublication.MANIFEST, new String(manifest, StandardCharsets.US_ASCII));
        return manifest;
    }

    @Test
    void theManifestHasOneLinePerFileSortedByNameInByteOrder() {
        SortedMap<String, String> hashes = new TreeMap<>();
        hashes.put("b.pdf", "2".repeat(64));
        hashes.put("B.pdf", "1".repeat(64));
        hashes.put("a.pdf", "3".repeat(64));
        assertThat(new String(StatementFolders.manifest(hashes), StandardCharsets.US_ASCII)).isEqualTo(
                "1".repeat(64) + "  B.pdf\n" + "3".repeat(64) + "  a.pdf\n" + "2".repeat(64) + "  b.pdf\n");
    }

    @Test
    void aMatchingFolderGivesItsManifestsSha256() throws IOException {
        byte[] manifest = legacyFolder();
        assertThat(StatementFolders.verify(folder, legacy(), Set.of())).isEqualTo(OutputPublication.sha256(manifest));
    }

    @Test
    void theFinalFolderMayHoldItsClaimAndNothingElse() throws IOException {
        byte[] manifest = legacyFolder();
        write(FolderPublication.CLAIM, new String(manifest, StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> StatementFolders.verify(folder, legacy(), Set.of()))
                .isInstanceOf(PublicationException.class);
        assertThat(StatementFolders.verify(folder, legacy(), Set.of(FolderPublication.CLAIM)))
                .isEqualTo(OutputPublication.sha256(manifest));
    }

    @Test
    void anExtraOrMissingFileIsAMismatch() throws IOException {
        legacyFolder();
        write("other.txt", "x");
        assertThatThrownBy(() -> StatementFolders.verify(folder, legacy(), Set.of()))
                .isInstanceOf(PublicationException.class).hasMessageContaining("missing 0, other 1")
                .message().doesNotContain("other.txt").doesNotContain(root.toString());
        Files.delete(folder.resolve("other.txt"));
        Files.delete(folder.resolve(StatementFolders.HTML));
        assertThatThrownBy(() -> StatementFolders.verify(folder, legacy(), Set.of()))
                .isInstanceOf(PublicationException.class).hasMessageContaining("missing 1, other 0");
    }

    @Test
    void aChangedFileIsAMismatch() throws IOException {
        legacyFolder();
        Files.writeString(folder.resolve(StatementFolders.TEXT), "TEXT\n", StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> StatementFolders.verify(folder, legacy(), Set.of()))
                .isInstanceOf(PublicationException.class).hasMessageContaining("is not the manifest");
    }

    @Test
    void anAccountFileWhoseHashDiffersFromItsRowIsAMismatch() throws IOException {
        write("00000000001.pdf", "pdf");
        write(StatementFolders.INDEX, "index");
        write(FolderPublication.MANIFEST, "not read: the account file fails first\n");
        SortedMap<String, String> expected = new TreeMap<>();
        expected.put("00000000001.pdf", "0".repeat(64));
        expected.put(StatementFolders.INDEX, null);
        assertThatThrownBy(() -> StatementFolders.verify(folder, expected, Set.of()))
                .isInstanceOf(PublicationException.class).hasMessageContaining("account row")
                .message().doesNotContain("00000000001").doesNotContain(root.toString());
    }

    @Test
    void aMissingFolderIsAMismatch() {
        assertThatThrownBy(() -> StatementFolders.verify(root.resolve("absent"), legacy(), Set.of()))
                .isInstanceOf(PublicationException.class).hasMessageContaining("is missing")
                .message().doesNotContain(root.toString());
    }
}
