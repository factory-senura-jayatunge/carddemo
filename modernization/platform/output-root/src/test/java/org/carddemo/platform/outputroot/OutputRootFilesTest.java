package org.carddemo.platform.outputroot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.carddemo.platform.outputroot.OutputRoot.Instance;
import org.carddemo.platform.outputroot.OutputRoot.Kept;
import org.carddemo.platform.outputroot.OutputRootException.Code;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Creation, validation and listing on the local file system. Each case asserts with file lists and
 * hashes taken before and after, not by statement.
 */
class OutputRootFilesTest {

    static final Instance INSTANCE = Instance.of("carddemo", OutputRootFormsTest.ID);
    static final Instance OTHER_INSTANCE = Instance.of("carddemo", "11111111-2222-4333-8444-555555555555");

    @TempDir
    Path temp;
    Path var;

    @BeforeEach
    void varDir() {
        var = OutputRoot.varDir(Map.of("CARDDEMO_VAR_DIR", temp.resolve("var").toString())::get);
    }

    @Test
    void ensureCreatesTheRootWithItsMarkerAndPrivateModes() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);

        assertThat(root).isEqualTo(OutputRoot.root(var, INSTANCE));
        assertThat(Files.readAllBytes(root.resolve(".instance"))).isEqualTo(INSTANCE.marker());
        for (Path dir : List.of(var.resolve("out"), root.getParent(), root)) {
            assertThat(mode(dir)).as(dir.toString()).isEqualTo("rwx------");
        }
        assertThat(mode(root.resolve(".instance"))).isEqualTo("rw-------");
        assertThat(names(root.getParent())).containsExactly(INSTANCE.instanceText());
        assertThat(names(root)).containsExactly(".instance");
        assertThat(OutputRoot.resolve(var, INSTANCE)).contains(root);
    }

    @Test
    void ensureAgainChangesNothing() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Map<String, String> before = snapshot(var);
        assertThat(OutputRoot.ensure(var, INSTANCE)).isEqualTo(root);
        assertThat(snapshot(var)).isEqualTo(before);
    }

    @Test
    void resolveIsEmptyAtEveryMissingLevelAndCreatesNothing() throws Exception {
        assertThat(OutputRoot.resolve(var, INSTANCE)).isEmpty();
        assertThat(var).doesNotExist();
        Files.createDirectories(var.resolve("out"), private700());
        assertThat(OutputRoot.resolve(var, INSTANCE)).isEmpty();
        Files.createDirectory(var.resolve("out").resolve(INSTANCE.component()), private700());
        assertThat(OutputRoot.resolve(var, INSTANCE)).isEmpty();
        Map<String, String> before = snapshot(var);
        assertThat(OutputRoot.resolve(var, INSTANCE)).isEmpty();
        assertThat(snapshot(var)).isEqualTo(before);
    }

    @Test
    void aForeignMarkerIsAMismatchAndItsTreeIsUnchanged() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Files.writeString(root.resolve("report.txt"), "kept");
        Files.write(root.resolve(".instance"), OTHER_INSTANCE.marker());
        assertRefused(Code.OUTPUT_ROOT_MISMATCH);

        Files.write(root.resolve(".instance"), Instance.of("carddemo_test", INSTANCE.instanceText()).marker());
        assertRefused(Code.OUTPUT_ROOT_MISMATCH);

        Files.write(root.resolve(".instance"), (new String(INSTANCE.marker(), StandardCharsets.US_ASCII) + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        assertRefused(Code.OUTPUT_ROOT_MISMATCH);
    }

    @Test
    void aRootWithoutItsMarkerIsIncompleteAndNotRepaired() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Files.delete(root.resolve(".instance"));
        Files.writeString(root.resolve("report.txt"), "kept");
        assertRefused(Code.OUTPUT_ROOT_INCOMPLETE);
    }

    @Test
    void anEmptyFolderAtTheRootsNameIsIncompleteAndNotReplaced() throws Exception {
        Path root = OutputRoot.root(var, INSTANCE);
        Files.createDirectories(root.getParent().getParent(), private700());
        Files.createDirectory(root.getParent(), private700());
        Files.createDirectory(root, private700());
        Object key = Files.readAttributes(root, BasicFileAttributes.class).fileKey();
        assertRefused(Code.OUTPUT_ROOT_INCOMPLETE);
        assertThat(Files.readAttributes(root, BasicFileAttributes.class).fileKey()).isEqualTo(key);
        assertThat(names(root.getParent())).containsExactly(INSTANCE.instanceText());
    }

    @Test
    void aLinkAtAnyManagedLevelIsRefusedAndNotFollowed() throws Exception {
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"), private700());
        Path elsewhereRoot = OutputRoot.ensure(elsewhere, INSTANCE);
        Files.createDirectories(var);
        Map<String, String> target = snapshot(elsewhere);

        Files.createSymbolicLink(var.resolve("out"), elsewhere.resolve("out"));
        assertRefused(Code.OUTPUT_PERMISSIONS);
        Files.delete(var.resolve("out"));

        Files.createDirectory(var.resolve("out"), private700());
        Files.createSymbolicLink(var.resolve("out").resolve(INSTANCE.component()), elsewhereRoot.getParent());
        assertRefused(Code.OUTPUT_PERMISSIONS);
        Files.delete(var.resolve("out").resolve(INSTANCE.component()));

        Path component = Files.createDirectory(var.resolve("out").resolve(INSTANCE.component()), private700());
        Files.createSymbolicLink(component.resolve(INSTANCE.instanceText()), elsewhereRoot);
        assertRefused(Code.OUTPUT_PERMISSIONS);
        Files.delete(component.resolve(INSTANCE.instanceText()));

        Path root = Files.createDirectory(component.resolve(INSTANCE.instanceText()), private700());
        Files.createSymbolicLink(root.resolve(".instance"), elsewhereRoot.resolve(".instance"));
        assertRefused(Code.OUTPUT_PERMISSIONS);

        assertThat(snapshot(elsewhere)).isEqualTo(target);
    }

    @Test
    void groupOrOtherBitsOnAnExistingManagedPathAreRefusedAndNeverChanged() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        for (Path path : List.of(var.resolve("out"), root.getParent(), root)) {
            for (String mode : List.of("rwxr-x---", "rwx---r--", "rwx-w----")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
                assertRefused(Code.OUTPUT_PERMISSIONS);
                assertThat(mode(path)).isEqualTo(mode);
            }
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        Path marker = root.resolve(".instance");
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-r-----"));
        assertRefused(Code.OUTPUT_PERMISSIONS);
        assertThat(mode(marker)).isEqualTo("rw-r-----");
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
        assertThat(OutputRoot.resolve(var, INSTANCE)).contains(root);
    }

    @Test
    void aFileSystemWithoutPosixOwnerAndModeIsRefusedAndNothingIsCreated() throws Exception {
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("plain.zip"), Map.of("create", "true"))) {
            assertThat(zip.supportedFileAttributeViews()).doesNotContain("posix");
            Path zipVar = zip.getPath("/var");
            assertThatThrownBy(() -> OutputRoot.ensure(zipVar, INSTANCE))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThat(zipVar.resolve("out")).doesNotExist();

            Path root = Files.createDirectories(OutputRoot.root(zipVar, INSTANCE));
            Files.write(root.resolve(".instance"), INSTANCE.marker());
            Files.createDirectories(root.resolve("reports"));
            List<String> before = tree(zipVar);
            assertThatThrownBy(() -> OutputRoot.resolve(zipVar, INSTANCE))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThatThrownBy(() -> OutputRoot.ensure(zipVar, INSTANCE))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThatThrownBy(() -> OutputRoot.ensureDirectory(root, "reports"))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertExistingFileRefused(root, Code.OUTPUT_PERMISSIONS);
            assertThat(tree(zipVar)).isEqualTo(before);
        }
    }

    @Test
    void aFileSystemWithModesButNoNumericOwnerIsRefused() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"));
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("posix.zip"),
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            assertThat(zip.supportedFileAttributeViews()).contains("posix").doesNotContain("unix");
            Path zipVar = zip.getPath("/var");
            assertThatThrownBy(() -> OutputRoot.ensure(zipVar, INSTANCE))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThat(zipVar.resolve("out").resolve(INSTANCE.component())).doesNotExist();
        }
    }

    @Test
    void aCrashBeforeTheRenameLeavesOnlyASiblingThatNeverBlocks() throws Exception {
        Path component = Files.createDirectories(var.resolve("out").resolve(INSTANCE.component()));
        Files.setPosixFilePermissions(var.resolve("out"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(component, PosixFilePermissions.fromString("rwx------"));
        Path sibling = Files.createDirectory(component.resolve("." + INSTANCE.instanceText() + "." + UUID.randomUUID() + ".tmp"),
                private700());
        Files.write(sibling.resolve(".instance"), INSTANCE.marker());
        Map<String, String> siblingBefore = snapshot(sibling);

        assertThat(OutputRoot.resolve(var, INSTANCE)).isEmpty();
        Path root = OutputRoot.ensure(var, INSTANCE);

        assertThat(Files.readAllBytes(root.resolve(".instance"))).isEqualTo(INSTANCE.marker());
        assertThat(snapshot(sibling)).isEqualTo(siblingBefore);
        assertThat(OutputRoot.kept(var, INSTANCE)).containsExactly(new Kept(Kept.Kind.SIBLING, sibling, 1));
    }

    /** The guarantee ensure's rename rests on: rename(2) never replaces a directory that has an entry. */
    @Test
    void theRenameNeverReplacesAPopulatedRoot() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Files.writeString(root.resolve("report.txt"), "winner");
        Path sibling = Files.createDirectory(root.resolveSibling("." + INSTANCE.instanceText() + "." + UUID.randomUUID() + ".tmp"),
                private700());
        Files.write(sibling.resolve(".instance"), INSTANCE.marker());
        Map<String, String> before = snapshot(root);

        assertThat(OutputRoot.rename(sibling, root)).isFalse();
        assertThatThrownBy(() -> Files.move(sibling, root, StandardCopyOption.ATOMIC_MOVE))
                .isInstanceOf(FileSystemException.class);
        Files.delete(root.resolve(".instance"));
        Map<String, String> withoutMarker = snapshot(root);
        assertThatThrownBy(() -> Files.move(sibling, root, StandardCopyOption.ATOMIC_MOVE))
                .isInstanceOf(FileSystemException.class);

        assertThat(snapshot(root)).isEqualTo(withoutMarker);
        assertThat(before).containsKeys(".instance", "report.txt");
        assertThat(snapshot(sibling)).containsOnlyKeys("/", ".instance");
    }

    @Test
    void aPlainFileAtTheRootsNameIsRefusedAndNotReplaced() throws Exception {
        Path root = OutputRoot.root(var, INSTANCE);
        Files.createDirectories(root.getParent(), private700());
        Files.setPosixFilePermissions(var.resolve("out"), PosixFilePermissions.fromString("rwx------"));
        Files.writeString(root, "not a root", StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rw-------"));
        assertRefused(Code.OUTPUT_PERMISSIONS);

        Path sibling = sibling(root);
        Map<String, String> before = snapshot(root.getParent());
        assertThat(OutputRoot.rename(sibling, root)).isFalse();
        assertThatThrownBy(() -> Files.move(sibling, root, StandardCopyOption.ATOMIC_MOVE))
                .isInstanceOf(FileSystemException.class);
        assertThat(snapshot(root.getParent())).isEqualTo(before);
        assertThat(Files.readString(root, StandardCharsets.US_ASCII)).isEqualTo("not a root");
    }

    @Test
    void theRenameRefusesALinkAtTheRootsNameAndLeavesItsTarget() throws Exception {
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"), private700());
        Path root = OutputRoot.root(var, INSTANCE);
        Files.createDirectories(root.getParent(), private700());
        Files.createSymbolicLink(root, elsewhere);
        Path sibling = sibling(root);
        Map<String, String> before = snapshot(temp);

        assertThatThrownBy(() -> OutputRoot.rename(sibling, root))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
        assertThatThrownBy(() -> Files.move(sibling, root, StandardCopyOption.ATOMIC_MOVE))
                .isInstanceOf(FileSystemException.class);
        assertThat(snapshot(temp)).isEqualTo(before);
        assertThat(names(elsewhere)).isEmpty();
    }

    @Test
    void aRenameIntoAFreeNameLeavesOnlyTheRootWithTheExactMarker() throws Exception {
        Path root = OutputRoot.root(var, INSTANCE);
        Files.createDirectories(root.getParent(), private700());
        Path sibling = sibling(root);

        assertThat(OutputRoot.rename(sibling, root)).isTrue();

        assertThat(sibling).doesNotExist();
        assertThat(names(root.getParent())).containsExactly(INSTANCE.instanceText());
        assertThat(Files.readAllBytes(root.resolve(".instance"))).isEqualTo(INSTANCE.marker());
    }

    /** The owner check compares uids with a file the process created, not the user.name property. */
    @Test
    void theOwnerCheckIgnoresUserName() throws Exception {
        Process process = ensureProcess(0, "-Duser.name=carddemo-nobody");
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(OutputRoot.resolve(var, INSTANCE)).contains(OutputRoot.root(var, INSTANCE));
    }

    @Test
    void racingThreadsGetOneRootAndLeaveNoSibling() throws Exception {
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Path> task = () -> {
                    start.await();
                    return OutputRoot.ensure(var, INSTANCE);
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Path> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(OutputRoot.root(var, INSTANCE));
            }
        } finally {
            pool.shutdownNow();
        }
        Path root = OutputRoot.root(var, INSTANCE);
        assertThat(names(root.getParent())).containsExactly(INSTANCE.instanceText());
        assertThat(snapshot(root)).containsOnlyKeys("/", ".instance");
        assertThat(Files.readAllBytes(root.resolve(".instance"))).isEqualTo(INSTANCE.marker());
    }

    @Test
    void racingProcessesGetOneRootAndLeaveNoSibling() throws Exception {
        List<Process> processes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            processes.add(ensureProcess(i));
        }
        for (Process process : processes) {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output.strip()).isEqualTo(OutputRoot.root(var, INSTANCE).toString());
        }
        Path root = OutputRoot.root(var, INSTANCE);
        assertThat(names(root.getParent())).containsExactly(INSTANCE.instanceText());
        assertThat(snapshot(root)).containsOnlyKeys("/", ".instance");
    }

    @Test
    void ensureDirectoryCreatesPrivateFoldersAndRefusesTraversal() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Path dir = OutputRoot.ensureDirectory(root, "reports", "tranrept", "1");
        assertThat(dir).isEqualTo(root.resolve("reports/tranrept/1"));
        for (Path path : List.of(root.resolve("reports"), root.resolve("reports/tranrept"), dir)) {
            assertThat(mode(path)).isEqualTo("rwx------");
        }
        for (String segment : new String[] {"..", ".", "a/b", "", "a b", "../x", null}) {
            assertThatThrownBy(() -> OutputRoot.ensureDirectory(root, "reports", segment)).as(String.valueOf(segment))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
        }
        Files.setPosixFilePermissions(root.resolve("reports"), PosixFilePermissions.fromString("rwxrwx---"));
        assertThatThrownBy(() -> OutputRoot.ensureDirectory(root, "reports", "tranrept"))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
        assertThat(mode(root.resolve("reports"))).isEqualTo("rwxrwx---");
    }

    @Test
    void privateDirectoryCreatesPrivateLevelsAndLeavesAnOpenBaseAsItIs() throws Exception {
        for (String baseMode : List.of("rwxr-xr-x", "rwxrwxrwx")) {
            Path base = Files.createDirectories(temp.resolve("base-" + baseMode.replace('-', '_')));
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString(baseMode));

            Path dir = OutputRoot.privateDirectory(base, "private", "transaction-service");

            assertThat(dir).isEqualTo(base.resolve("private/transaction-service"));
            assertThat(mode(base)).isEqualTo(baseMode);
            assertThat(mode(base.resolve("private"))).isEqualTo("rwx------");
            assertThat(mode(dir)).isEqualTo("rwx------");
            Map<String, String> before = snapshot(base);
            assertThat(OutputRoot.privateDirectory(base, "private", "transaction-service")).isEqualTo(dir);
            assertThat(snapshot(base)).isEqualTo(before);
            assertThat(mode(base)).isEqualTo(baseMode);
        }
    }

    @Test
    void privateDirectoryRefusesALevelItCannotTrustAndCreatesNothingBelowIt() throws Exception {
        Path base = Files.createDirectories(temp.resolve("base"));
        Path level = Files.createDirectory(base.resolve("private"), private700());

        Files.setPosixFilePermissions(level, PosixFilePermissions.fromString("rwxr-x---"));
        assertPrivateDirectoryRefused(base, Code.OUTPUT_PERMISSIONS);
        assertThat(mode(level)).isEqualTo("rwxr-x---");
        Files.delete(level);

        Files.writeString(level, "x");
        assertPrivateDirectoryRefused(base, Code.OUTPUT_PERMISSIONS);
        Files.delete(level);

        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"), private700());
        Files.createSymbolicLink(level, elsewhere);
        Map<String, String> target = snapshot(elsewhere);
        assertPrivateDirectoryRefused(base, Code.OUTPUT_PERMISSIONS);
        assertThat(snapshot(elsewhere)).isEqualTo(target);
        assertThat(elsewhere.resolve("transaction-service")).doesNotExist();
    }

    @Test
    void privateDirectoryFollowsALinkInTheBase() throws Exception {
        Path real = Files.createDirectories(temp.resolve("real-base"));
        Path linked = Files.createSymbolicLink(temp.resolve("linked-base"), real);

        Path dir = OutputRoot.privateDirectory(linked, "private");

        assertThat(dir).isEqualTo(linked.resolve("private"));
        assertThat(mode(real.resolve("private"))).isEqualTo("rwx------");
    }

    @Test
    void privateDirectoryRefusesABadBaseOrLevelAndCreatesNothing() throws Exception {
        Path base = Files.createDirectories(temp.resolve("base"));
        Path file = Files.writeString(temp.resolve("file"), "x");
        Path removed = Files.createDirectories(temp.resolve("removed"));
        Files.delete(removed);
        Map<String, String> before = snapshot(temp);
        List<Callable<Path>> calls = new ArrayList<>();
        calls.add(() -> OutputRoot.privateDirectory(null, "private"));
        calls.add(() -> OutputRoot.privateDirectory(Path.of("relative"), "private"));
        calls.add(() -> OutputRoot.privateDirectory(base, (String[]) null));
        calls.add(() -> OutputRoot.privateDirectory(base));
        calls.add(() -> OutputRoot.privateDirectory(temp.resolve("missing"), "private"));
        calls.add(() -> OutputRoot.privateDirectory(removed, "private"));
        calls.add(() -> OutputRoot.privateDirectory(file, "private"));
        for (String segment : new String[] {"..", ".", "a/b", "", "a b", "../x", null}) {
            calls.add(() -> OutputRoot.privateDirectory(base, "private", segment));
        }
        for (Callable<Path> call : calls) {
            assertThatThrownBy(call::call)
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
        }
        assertThat(snapshot(temp)).isEqualTo(before);
    }

    @Test
    void privateDirectoryReportsAFailedCreationAsAnIoError() throws Exception {
        Path base = Files.createDirectories(temp.resolve("base"));
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(base), "a superuser can write a 0500 folder");
            assertThatThrownBy(() -> OutputRoot.privateDirectory(base, "private"))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            assertThat(names(base)).isEmpty();
        } finally {
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void privateDirectoryRefusesAFileSystemWithoutOwnerOrModeBeforeCreating() throws Exception {
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("plain.zip"), Map.of("create", "true"))) {
            Path base = Files.createDirectories(zip.getPath("/base"));
            assertThatThrownBy(() -> OutputRoot.privateDirectory(base, "private"))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThat(base.resolve("private")).doesNotExist();
        }
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"));
        try (FileSystem zip = FileSystems.newFileSystem(temp.resolve("posix.zip"),
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            Path base = Files.createDirectories(zip.getPath("/base"));
            assertThatThrownBy(() -> OutputRoot.privateDirectory(base, "private"))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_PERMISSIONS));
            assertThat(base.resolve("private")).doesNotExist();
        }
    }

    @Test
    void racingThreadsGetOnePrivateDirectory() throws Exception {
        Path base = Files.createDirectories(temp.resolve("base"));
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Path> task = () -> {
                    start.await();
                    return OutputRoot.privateDirectory(base, "private", "transaction-service");
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Path> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(base.resolve("private/transaction-service"));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(names(base)).containsExactly("private");
        assertThat(names(base.resolve("private"))).containsExactly("transaction-service");
        assertThat(mode(base.resolve("private/transaction-service"))).isEqualTo("rwx------");
    }

    @Test
    void existingFileChecksEveryFolderAndTheFile() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        assertThat(OutputRoot.existingFile(root, "reports", "tranrept", "1", "TRANREPT.txt")).isEmpty();
        Path dir = OutputRoot.ensureDirectory(root, "reports", "tranrept", "1");
        assertThat(OutputRoot.existingFile(root, "reports", "tranrept", "1", "TRANREPT.txt")).isEmpty();
        Path file = Files.createFile(dir.resolve("TRANREPT.txt"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        assertThat(OutputRoot.existingFile(root, "reports", "tranrept", "1", "TRANREPT.txt")).contains(file);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertExistingFileRefused(root, Code.OUTPUT_PERMISSIONS);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertExistingFileRefused(root, Code.OUTPUT_PERMISSIONS);
        Files.delete(file);

        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"), private700());
        Files.writeString(elsewhere.resolve("TRANREPT.txt"), "foreign");
        Files.delete(dir);
        Files.createSymbolicLink(dir, elsewhere);
        assertExistingFileRefused(root, Code.OUTPUT_PERMISSIONS);
        assertThat(Files.readString(elsewhere.resolve("TRANREPT.txt"))).isEqualTo("foreign");

        assertThatThrownBy(() -> OutputRoot.existingFile(root, "reports", "..", "x"))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void keptListsEverythingButTheCurrentRootAndChangesNothing() throws Exception {
        Path root = OutputRoot.ensure(var, INSTANCE);
        Path old = OutputRoot.ensure(var, OTHER_INSTANCE);
        Files.writeString(OutputRoot.ensureDirectory(old, "reports", "tranrept", "1").resolve("TRANREPT.txt"), "old");
        Path sibling = Files.createDirectory(root.resolveSibling("." + INSTANCE.instanceText() + "." + UUID.randomUUID() + ".tmp"));
        Path stray = Files.createDirectory(root.resolveSibling("stray"));
        Files.createDirectories(var.resolve("reports/tranrept"));
        Files.writeString(var.resolve("reports/tranrept/TRANREPT.txt"), "pre");
        Files.createDirectories(var.resolve("dailytran/archive"));
        Files.writeString(var.resolve("dailytran/archive/a.txt"), "a");
        Files.writeString(var.resolve("dailytran/archive/b.txt"), "b");
        Files.createSymbolicLink(var.resolve("statements"), temp);
        Files.writeString(root.resolve("current.txt"), "current");
        Map<String, String> before = snapshot(temp);

        List<Kept> kept = OutputRoot.kept(var, INSTANCE);

        assertThat(kept).containsExactlyInAnyOrder(
                new Kept(Kept.Kind.ROOT, old, 2),
                new Kept(Kept.Kind.SIBLING, sibling, 0),
                new Kept(Kept.Kind.OTHER, stray, 0),
                new Kept(Kept.Kind.PRE_INSTANCE, var.resolve("reports"), 1),
                new Kept(Kept.Kind.PRE_INSTANCE, var.resolve("statements"), 0),
                new Kept(Kept.Kind.PRE_INSTANCE, var.resolve("dailytran/archive"), 2));
        assertThat(snapshot(temp)).isEqualTo(before);
    }

    @Test
    void twoDatabasesNeverShareARoot() throws Exception {
        Path demo = OutputRoot.ensure(var, INSTANCE);
        Path e2e = OutputRoot.ensure(var, Instance.of("carddemo_batch_e2e", INSTANCE.instanceText()));
        assertThat(demo.getParent()).isNotEqualTo(e2e.getParent());
        assertThat(OutputRoot.resolve(var, INSTANCE)).contains(demo);
        assertThat(OutputRoot.kept(var, INSTANCE)).isEmpty();
    }

    private Process ensureProcess(int n, String... jvmOptions) throws Exception {
        String classPath = String.join(java.io.File.pathSeparator,
                Path.of(OutputRoot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(EnsureMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-cp", classPath, EnsureMain.class.getName(),
                var.toString(), INSTANCE.database(), INSTANCE.instanceText().toUpperCase(java.util.Locale.ROOT)));
        return new ProcessBuilder(command).directory(Files.createDirectories(temp.resolve("cwd" + n)).toFile())
                .redirectErrorStream(true).start();
    }

    /** A complete sibling as ensure prepares it, next to {@code root}. */
    private static Path sibling(Path root) throws IOException {
        Path sibling = Files.createDirectory(root.resolveSibling("." + INSTANCE.instanceText() + "." + UUID.randomUUID() + ".tmp"),
                private700());
        Files.write(sibling.resolve(".instance"), INSTANCE.marker());
        Files.setPosixFilePermissions(sibling.resolve(".instance"), PosixFilePermissions.fromString("rw-------"));
        return sibling;
    }

    private void assertRefused(Code code) throws IOException {
        Map<String, String> before = snapshot(temp);
        assertThatThrownBy(() -> OutputRoot.resolve(var, INSTANCE))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThatThrownBy(() -> OutputRoot.ensure(var, INSTANCE))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(snapshot(temp)).isEqualTo(before);
    }

    private void assertPrivateDirectoryRefused(Path base, Code code) throws IOException {
        Map<String, String> before = snapshot(temp);
        assertThatThrownBy(() -> OutputRoot.privateDirectory(base, "private", "transaction-service"))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(snapshot(temp)).isEqualTo(before);
    }

    private static void assertExistingFileRefused(Path root, Code code) {
        assertThatThrownBy(() -> OutputRoot.existingFile(root, "reports", "tranrept", "1", "TRANREPT.txt"))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static java.nio.file.attribute.FileAttribute<?> private700() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    private static List<String> tree(Path start) throws IOException {
        try (var paths = Files.walk(start)) {
            return paths.map(Path::toString).sorted().toList();
        }
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static List<String> names(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Relative path to a file's sha256, a link's target, or a folder's mode; links are not followed. */
    static Map<String, String> snapshot(Path start) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
            return snapshot;
        }
        Files.walkFileTree(start, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                snapshot.put(start.relativize(dir) + "/", mode(dir));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String key = start.relativize(file).toString();
                if (attributes.isSymbolicLink()) {
                    snapshot.put(key, "-> " + Files.readSymbolicLink(file));
                } else {
                    snapshot.put(key, mode(file) + " " + sha256(Files.readAllBytes(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return snapshot;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
