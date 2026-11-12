package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {

    @TempDir
    Path tmp;

    @Test
    void writesOwnerOnlyLineSequentialFiles() throws Exception {
        Path dir = tmp.resolve("POSTTRAN");
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
        Path dir = Files.createDirectory(tmp.resolve("d"), mode("rwx------"));
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

    // FX-2 to FX-8: every destination, checked and never repaired; messages name the option and the rule.

    private static FileAttribute<Set<PosixFilePermission>> mode(String mode) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode));
    }

    private static String permissions(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
    }

    private long uid() {
        return ProcessUid.current().orElseThrow();
    }

    private void refused(String option, Path dir, OptionalLong uid, Class<? extends RuntimeException> type) {
        assertThatThrownBy(() -> ExportFiles.open(option, dir, uid)).isInstanceOf(type)
                .hasMessageStartingWith(option).hasMessageNotContaining(tmp.toString())
                .hasMessageNotContaining(dir.getFileName().toString()).hasNoCause();
    }

    @Test
    void theParentMustExistAndIsNeverCreated() {
        Path dir = tmp.resolve("CANARYPARENT").resolve("CANARYDEST");
        refused("--export", dir, OptionalLong.of(uid()), UsageException.class);
        assertThat(tmp.resolve("CANARYPARENT")).doesNotExist();
    }

    @Test
    void theParentMustBeAPrivateFolderOfThisUser() throws Exception {
        Path file = Files.writeString(tmp.resolve("CANARYFILE"), "x");
        refused("--output-dir", file.resolve("CANARYDEST"), OptionalLong.of(uid()), UsageException.class);
        for (String m : new String[] {"rwxrwx---", "rwx---rwx", "rwx-w----", "rwx----w-"}) {
            Path parent = Files.createDirectory(tmp.resolve("p" + m.replace('-', '_')));
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString(m));
            refused("--output-dir", parent.resolve("CANARYDEST"), OptionalLong.of(uid()), UsageException.class);
            assertThat(parent.resolve("CANARYDEST")).doesNotExist();
        }
        Path readable = Files.createDirectory(tmp.resolve("readable"));
        Files.setPosixFilePermissions(readable, PosixFilePermissions.fromString("rwxr-xr-x"));
        ExportFiles.open("--output-dir", readable.resolve("dest"), OptionalLong.of(uid()));
        assertThat(permissions(readable.resolve("dest"))).isEqualTo("rwx------");
    }

    @Test
    void anotherOwnerOrNoUserIdIsRefused() throws Exception {
        // chown needs root, so the process's user ID is stubbed instead: the folders then have another owner.
        refused("--export", tmp.resolve("CANARYDEST"), OptionalLong.of(uid() + 1), UsageException.class);
        assertThat(tmp.resolve("CANARYDEST")).doesNotExist();
        refused("--export", tmp.resolve("CANARYDEST"), OptionalLong.empty(), UsageException.class);
        assertThat(tmp.resolve("CANARYDEST")).doesNotExist();
    }

    @Test
    void anExistingDestinationIsCheckedAndNeverRepaired() throws Exception {
        Path target = Files.createDirectory(tmp.resolve("target"), mode("rwx------"));
        Path link = Files.createSymbolicLink(tmp.resolve("CANARYLINK"), target);
        refused("--export", link, OptionalLong.of(uid()), UsageException.class);
        Path file = Files.writeString(tmp.resolve("CANARYPLAIN"), "");
        refused("--export", file, OptionalLong.of(uid()), UsageException.class);
        for (String m : new String[] {"rwxr-x---", "rwx--x---", "rwx---r--", "rwx-----x", "rwxrwxrwx"}) {
            Path open = Files.createDirectory(tmp.resolve("CANARYOPEN" + m.replace('-', '_')));
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString(m));
            refused("--output-dir", open, OptionalLong.of(uid()), UsageException.class);
            assertThat(permissions(open)).as("never changed").isEqualTo(m);
        }
        refused("--export", target, OptionalLong.of(uid() + 1), UsageException.class);
        Path narrower = Files.createDirectory(tmp.resolve("narrower"), mode("r-x------"));
        ExportFiles.open("--export", narrower, OptionalLong.of(uid()));
        assertThat(permissions(narrower)).as("never widened").isEqualTo("r-x------");
        ExportFiles.open("--export", target, OptionalLong.of(uid()));
    }

    @Test
    void aMissingDestinationIsCreatedExactlyOwnerOnly() throws Exception {
        Path dir = tmp.resolve("made");
        ExportFiles.open("--output-dir", dir, OptionalLong.of(uid()));
        assertThat(permissions(dir)).isEqualTo("rwx------");
        assertThat(Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS)).isEqualTo((int) uid());
    }

    @Test
    void aDestinationThatCannotBeCreatedIsAnExportFailureWithoutCause() throws Exception {
        // Root can create in a read-only folder, so a root run must fail here, not skip.
        OptionalLong uid = ProcessUid.current();
        assertTrue(uid.isPresent() && uid.getAsLong() != 0,
                "this test must run as a user other than root, with a known user ID");
        Path parent = Files.createDirectory(tmp.resolve("ro"), mode("r-x------"));
        refused("--output-dir", parent.resolve("CANARYDEST"), OptionalLong.of(uid()), ExportException.class);
    }

    @Test
    void dotNamesAreRefused() {
        assertThatThrownBy(() -> ExportFiles.open("--export", tmp.resolve("."), OptionalLong.of(uid())))
                .isInstanceOf(UsageException.class);
        assertThatThrownBy(() -> ExportFiles.open("--export", tmp.resolve(".."), OptionalLong.of(uid())))
                .isInstanceOf(UsageException.class);
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
