package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.carddemo.platform.outputroot.OutputRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The staging setting's start resolution and the start clean-up of leftover snapshot names
 * (transaction-service.md 16, 3.5, 4 and 8), in a scratch CARDDEMO_VAR_DIR.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportStagingTest {

    private static final FileAttribute<?> OWNER_ONLY_DIR =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    /** Planted in values and names; it must never reach a message or a log. */
    private static final String SENTINEL = "zz9sentinel";

    @TempDir
    Path temp;
    Path var;

    @BeforeEach
    void varDir() throws IOException {
        Files.createDirectory(temp.resolve("var"));
        var = OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_VARIABLE, temp.resolve("var").toString())::get);
    }

    private ReportStaging resolve(String value) {
        Function<String, String> settings = name -> ReportStaging.PROPERTY.equals(name) ? value : null;
        return ReportStaging.resolve(var, settings);
    }

    private void refusedAtStart(String value, CapturedOutput output) {
        assertThatThrownBy(() -> resolve(value)).isInstanceOfSatisfying(IllegalArgumentException.class, e -> {
            assertThat(e.getMessage()).startsWith(ReportStaging.PROPERTY + " ").doesNotContain(SENTINEL);
            assertThat(e.getCause()).isNull();
        });
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    private static String leftover() {
        return "download-" + UUID.randomUUID() + ".tmp";
    }

    /** T2: values that name one folder when compared and another on disk, or no folder, fail the start. */
    @Test
    void aWrongValueFailsTheStartWithTheNameOnly(CapturedOutput output) throws IOException {
        Path a = Files.createDirectory(temp.resolve("a" + SENTINEL), OWNER_ONLY_DIR);
        Files.createDirectory(a.resolve("b"), OWNER_ONLY_DIR);
        Path link = Files.createSymbolicLink(temp.resolve("link" + SENTINEL), a.resolve("b"));
        for (String value : new String[] {"", SENTINEL + "/s", a + "/./s", a + "/../s", a + "/b/../s", link + "/../s",
                a + "/s/.", a + "/s/..", "/", a + "/s\0" + SENTINEL, a + "/s/" + SENTINEL + " x",
                temp.resolve("missing" + SENTINEL) + "/s"}) {
            refusedAtStart(value, output);
        }
        Path loop = temp.resolve("loop" + SENTINEL);
        Files.createSymbolicLink(loop, loop);
        refusedAtStart(loop + "/s", output);
        Path file = Files.createFile(temp.resolve("file" + SENTINEL));
        refusedAtStart(file + "/s", output);
    }

    /** Path.of collapses a double slash and drops a trailing one, so these name the folder s. */
    @Test
    void aDoubleOrTrailingSlashIsAccepted() throws IOException {
        Path a = Files.createDirectory(temp.resolve("a"), OWNER_ONLY_DIR);
        Path expected = a.toRealPath().resolve("s");
        assertThat(resolve(a + "//s").expected()).isEqualTo(expected);
        assertThat(resolve(a + "/s/").expected()).isEqualTo(expected);
        assertThat(resolve(null).expected()).isEqualTo(var.resolve("private").resolve("transaction-service"));
    }

    /** C3: no staging folder at, in or around CARDDEMO_VAR_DIR/out, through a link or an alias too. */
    @Test
    void aFolderAtOrAroundOutFailsTheStart(CapturedOutput output) throws IOException {
        Path out = Files.createDirectory(var.resolve("out"), OWNER_ONLY_DIR);
        Files.createDirectory(out.resolve("x"), OWNER_ONLY_DIR);
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), out);
        Path varAlias = Files.createSymbolicLink(temp.resolve("varalias"), var);
        for (String value : new String[] {out.toString(), out + "/s", out + "/x/s", alias + "/s",
                varAlias + "/out", var.toString()}) {
            assertThatThrownBy(() -> resolve(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ReportStaging.PROPERTY + " must not be CARDDEMO_VAR_DIR/out, inside it, or contain it");
        }
        assertThat(output.getAll()).doesNotContain(var.toString());
    }

    /** Item 4: own leftovers go; anything else stays and is counted; the log holds counts only. */
    @Test
    void theCleanUpDeletesOnlyItsOwnNames(CapturedOutput output) throws IOException {
        ReportStaging staging = resolve(null);
        Path folder = staging.folder();
        Path own = Files.createFile(folder.resolve(leftover()));
        Path other = Files.createFile(folder.resolve("keep-" + SENTINEL + ".tmp"));
        Path upper = Files.createFile(folder.resolve(leftover().toUpperCase().replace("DOWNLOAD-", "download-")
                .replace(".TMP", ".tmp")));
        Path target = Files.createFile(temp.resolve("target"));
        Path link = Files.createSymbolicLink(folder.resolve(leftover()), target);
        Path dir = Files.createDirectory(folder.resolve(leftover()));

        ReportStaging.CleanUp result = staging.cleanUp();
        assertThat(result).isEqualTo(new ReportStaging.CleanUp(null, 1, 4, 0, false));
        assertThat(own).doesNotExist();
        assertThat(other).exists();
        assertThat(upper).exists();
        assertThat(link).isSymbolicLink();
        assertThat(target).exists();
        assertThat(dir).isDirectory();
        assertThat(output.getAll()).contains("report staging clean-up: deleted 1, kept 4, failed 0, listing stopped false")
                .doesNotContain(SENTINEL, folder.toString(), "download-");
    }

    /** With an override, only the overridden folder is listed; the default one is never touched. */
    @Test
    void anOverrideIsTheOnlyFolderCleaned() throws IOException {
        Path base = Files.createDirectory(temp.resolve("base"), OWNER_ONLY_DIR);
        Path defaultLeftover = Files.createFile(resolve(null).folder().resolve(leftover()));
        ReportStaging staging = resolve(base + "/s");
        Path overrideLeftover = Files.createFile(staging.folder().resolve(leftover()));
        assertThat(staging.cleanUp()).isEqualTo(new ReportStaging.CleanUp(null, 1, 0, 0, false));
        assertThat(overrideLeftover).doesNotExist();
        assertThat(defaultLeftover).exists();
    }

    /** A folder that fails its check, or is a file, keeps every entry; the start goes on. */
    @Test
    void aFailedCheckDeletesNothing(CapturedOutput output) throws IOException {
        ReportStaging staging = resolve(null);
        Path folder = staging.folder();
        Path planted = Files.createFile(folder.resolve(leftover()));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(staging.cleanUp().skipped()).isEqualTo("OUTPUT_PERMISSIONS");
        assertThat(planted).exists();

        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        Path moved = Files.move(folder, temp.resolve("moved"));
        Files.createSymbolicLink(folder, moved);
        assertThat(staging.cleanUp().skipped()).isEqualTo("OUTPUT_PERMISSIONS");
        assertThat(moved.resolve(planted.getFileName())).exists();

        Files.delete(folder);
        Files.createFile(folder);
        assertThat(staging.cleanUp().skipped()).isEqualTo("OUTPUT_PERMISSIONS");
        assertThat(output.getAll()).contains("report staging clean-up: nothing deleted (OUTPUT_PERMISSIONS)")
                .doesNotContain(folder.toString(), "download-");
    }

    /** T3: the second level missing, a file of a matching name beside it: nothing is deleted, no path logged. */
    @Test
    void aMissingFolderIsLeftAlone(CapturedOutput output) throws IOException {
        Path privateDir = Files.createDirectory(var.resolve("private"), OWNER_ONLY_DIR);
        Path beside = Files.createFile(privateDir.resolve(leftover()));
        ReportStaging staging = resolve(null);
        assertThat(staging.cleanUp().skipped()).isEqualTo("NO_FOLDER");
        assertThat(beside).exists();
        assertThat(privateDir.resolve("transaction-service")).doesNotExist();
        assertThat(output.getAll()).doesNotContain(var.toString(), "download-");
    }

    /** The listing stops after its limit and leaves the rest. */
    @Test
    void theListingStopsAtItsLimit() throws IOException {
        ReportStaging staging = resolve(null);
        Path folder = staging.folder();
        for (int i = 0; i <= ReportStaging.CLEAN_UP_LIMIT; i++) {
            Files.createFile(folder.resolve(leftover()));
        }
        ReportStaging.CleanUp result = staging.cleanUp();
        assertThat(result.stopped()).isTrue();
        assertThat(result.deleted()).isEqualTo(ReportStaging.CLEAN_UP_LIMIT);
        try (var left = Files.list(folder)) {
            assertThat(left.count()).isEqualTo(1);
        }
    }

    /**
     * A read error of the listing itself is a DirectoryIteratorException, a RuntimeException: it ends
     * the clean-up as LIST_IO with the counts taken so far, never fails the start, and its message
     * (which can hold the folder) reaches no log. Both the secure and the plain listing.
     */
    @Test
    void aListingReadErrorEndsTheCleanUp(CapturedOutput output) throws IOException {
        boolean secureListing;
        try (DirectoryStream<Path> probe = Files.newDirectoryStream(var)) {
            secureListing = probe instanceof SecureDirectoryStream;
        }
        for (boolean secure : secureListing ? new boolean[] {true, false} : new boolean[] {false}) {
            for (int before : new int[] {0, 1}) {
                ReportStaging staging = ReportStaging.resolve(var, name -> null, failingAfter(before, secure));
                Path folder = staging.folder();
                Path planted = Files.createFile(folder.resolve(leftover()));
                ReportStaging.CleanUp result = staging.cleanUp();
                assertThat(result).as("secure %s, entries before the error %d", secure, before)
                        .isEqualTo(new ReportStaging.CleanUp(ReportStaging.LIST_IO, before, 0, 0, true));
                assertThat(Files.exists(planted)).isEqualTo(before == 0);
                Files.deleteIfExists(planted);
            }
        }
        assertThat(output.getAll()).contains("report staging clean-up: nothing deleted (LIST_IO)")
                .contains("report staging clean-up: ended early (LIST_IO), deleted 1, kept 0, failed 0")
                .doesNotContain(SENTINEL, var.toString(), "download-", "DirectoryIteratorException");
    }

    /** Hooks whose listing hands out {@code before} real entries, then fails as a lost folder would. */
    private static ReportStaging.Hooks failingAfter(int before, boolean secure) {
        return new ReportStaging.Hooks() {
            @Override
            public DirectoryStream<Path> listed(DirectoryStream<Path> entries) {
                return secure ? new FailingSecure((SecureDirectoryStream<Path>) entries, before)
                        : new FailingPlain(entries, before);
            }
        };
    }

    private static Iterator<Path> failing(Iterator<Path> real, int before) {
        return new Iterator<>() {
            int given;

            @Override
            public boolean hasNext() {
                if (given < before && real.hasNext()) {
                    return true;
                }
                throw new DirectoryIteratorException(new IOException("cannot read " + SENTINEL));
            }

            @Override
            public Path next() {
                hasNext();
                given++;
                return real.next();
            }
        };
    }

    private record FailingPlain(DirectoryStream<Path> real, int before) implements DirectoryStream<Path> {
        @Override
        public Iterator<Path> iterator() {
            return failing(real.iterator(), before);
        }

        @Override
        public void close() throws IOException {
            real.close();
        }
    }

    private record FailingSecure(SecureDirectoryStream<Path> real, int before) implements SecureDirectoryStream<Path> {
        @Override
        public Iterator<Path> iterator() {
            return failing(real.iterator(), before);
        }

        @Override
        public void close() throws IOException {
            real.close();
        }

        @Override
        public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options) throws IOException {
            return real.newDirectoryStream(path, options);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attrs) throws IOException {
            return real.newByteChannel(path, options, attrs);
        }

        @Override
        public void deleteFile(Path path) throws IOException {
            real.deleteFile(path);
        }

        @Override
        public void deleteDirectory(Path path) throws IOException {
            real.deleteDirectory(path);
        }

        @Override
        public void move(Path srcpath, SecureDirectoryStream<Path> targetdir, Path targetpath) throws IOException {
            real.move(srcpath, targetdir, targetpath);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
            return real.getFileAttributeView(type);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return real.getFileAttributeView(path, type, options);
        }
    }
}
