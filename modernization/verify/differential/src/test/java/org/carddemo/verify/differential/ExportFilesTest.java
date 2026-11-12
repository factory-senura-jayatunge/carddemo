package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {

    @TempDir
    Path tmp;

    @Test
    void writesOwnerOnlyLineSequentialFiles() throws Exception {
        Path dir = tmp.resolve("new/POSTTRAN");
        ExportFiles files = ExportFiles.open(dir);
        files.records("A.txt", List.of("ab".getBytes(StandardCharsets.ISO_8859_1), new byte[] {(byte) 0xE9, 'c'}));
        files.records("EMPTY.txt", List.of());
        assertThat(Files.readAllBytes(dir.resolve("A.txt"))).containsExactly('a', 'b', '\n', 0xE9, 'c', '\n');
        assertThat(Files.size(dir.resolve("EMPTY.txt"))).isZero();
        try (var entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("A.txt", "EMPTY.txt");
        }
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("A.txt"))))
                .isEqualTo("rw-------");
    }

    @Test
    void anExistingFolderMustBeEmpty() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("d"));
        ExportFiles.open(dir);
        Files.writeString(dir.resolve("old.txt"), "x");
        assertThatThrownBy(() -> ExportFiles.open(dir)).isInstanceOf(UsageException.class);
        Path file = Files.writeString(tmp.resolve("f"), "x");
        assertThatThrownBy(() -> ExportFiles.open(file)).isInstanceOf(UsageException.class);
    }

    @Test
    void discardRemovesWhatWasWritten() throws Exception {
        Path dir = tmp.resolve("e");
        ExportFiles files = ExportFiles.open(dir);
        files.records("A.txt", List.of("a".getBytes(StandardCharsets.ISO_8859_1)));
        files.discard();
        try (var entries = Files.list(dir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void aFileThatAppearsAtItsNameIsNeverReplacedAndTheExportIsDiscarded() throws Exception {
        Path dir = tmp.resolve("g");
        ExportFiles files = ExportFiles.open(dir);
        files.records("B.txt", List.of("b".getBytes(StandardCharsets.ISO_8859_1)));
        Path theirs = Files.writeString(dir.resolve("A.txt"), "theirs");
        Object inode = Files.getAttribute(theirs, "unix:ino");

        assertThatThrownBy(() -> files.records("A.txt", List.of("a".getBytes(StandardCharsets.ISO_8859_1))))
                .isInstanceOf(ExportException.class)
                .hasMessage("A.txt exists already in the export folder; nothing is replaced");

        assertThat(Files.readString(theirs)).isEqualTo("theirs");
        assertThat(Files.getAttribute(theirs, "unix:ino")).isEqualTo(inode);
        try (var entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).as("B.txt discarded, no temporary left")
                    .containsExactly("A.txt");
        }
    }

    @Test
    void aTemporaryNameThatExistsIsNotThisExportsAndIsLeftAlone() throws Exception {
        Path dir = tmp.resolve("h");
        ExportFiles files = ExportFiles.open(dir);
        Path foreign = Files.writeString(dir.resolve(".A.txt.tmp"), "foreign");

        assertThatThrownBy(() -> files.records("A.txt", List.of("a".getBytes(StandardCharsets.ISO_8859_1))))
                .isInstanceOf(ExportException.class)
                .hasMessage("the temporary name of A.txt exists already in the export folder; nothing is replaced");

        assertThat(Files.readString(foreign)).isEqualTo("foreign");
        assertThat(dir.resolve("A.txt")).doesNotExist();
    }

    @Test
    void countsAreFlatJson() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("job", "POSTTRAN");
        counts.put("processed", 300L);
        counts.put("rc", 4L);
        assertThat(new String(CountsJson.of(counts), StandardCharsets.UTF_8))
                .isEqualTo("{\n  \"job\": \"POSTTRAN\",\n  \"processed\": 300,\n  \"rc\": 4\n}\n");
        counts.put("bad", "quote\"");
        assertThatThrownBy(() -> CountsJson.of(counts)).isInstanceOf(IllegalArgumentException.class);
    }
}
