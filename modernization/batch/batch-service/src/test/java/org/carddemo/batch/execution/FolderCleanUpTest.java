package org.carddemo.batch.execution;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.carddemo.batch.execution.FolderPublication.Step;
import org.carddemo.batch.execution.OutputPublication.Outcome;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Section 4.8's clean-up of folder outputs on the real file system, without a database: only the
 * working folders of terminal executions of the instance that no committed intent names are
 * removed, no link is followed, an unsafe folder or entry fails the start and keeps everything, and
 * the published names of a removed working folder keep their bytes and inodes.
 */
@ExtendWith(OutputCaptureExtension.class)
class FolderCleanUpTest {

    static final String PREFIX = "carddemo.test";
    static final JobInstance INSTANCE = new JobInstance(3L, "folderProbe");
    static final Map<String, String> FILES = Map.of("A.txt", "alpha", "B.txt", "bravo", "C.txt", "charlie");
    /**
     * Synthetic sensitive values as entry names, each a valid path segment: an SSN, a government ID,
     * a date of birth, an EFT account, a card number, a CVV and an account ID.
     */
    static final List<String> SENSITIVE_NAMES = List.of("123-45-6789", "GOV-A1B2C3D4", "1961-08-01",
            "EFT-0123456789", "0123456789012345", "CVV-737", "00000000099.pdf");

    @TempDir
    Path temp;

    Path kind;
    Path workRoot;
    final List<JobExecution> executions = new ArrayList<>();
    OutputPublication publication;

    record Entry(Object inode, FileTime modified, String content) {
    }

    @BeforeEach
    void roots() {
        kind = OutputRoot.ensureDirectory(temp, "statements");
        workRoot = OutputRoot.ensureDirectory(kind, ".work");
        JobRepository repository = (JobRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJobExecutions" -> {
                        assertThat(args[0]).isEqualTo(INSTANCE);
                        yield List.copyOf(executions);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        publication = new OutputPublication(repository, null, null);
    }

    private JobExecution execution(long id, BatchStatus status) {
        JobExecution execution = new JobExecution(id, INSTANCE, new JobParameters());
        execution.setStatus(status);
        executions.add(execution);
        return execution;
    }

    private static void name(JobExecution execution, String workFolder) {
        execution.getExecutionContext().putString(PREFIX + OutputPublication.WORK_PATH, workFolder);
        execution.getExecutionContext().putString(PREFIX + OutputPublication.SHA256, "0".repeat(64));
    }

    private Path work(String id, long execution) throws IOException {
        Path dir = FolderPublication.createWorkFolder(workRoot, id, execution);
        for (Map.Entry<String, String> file : new TreeMap<>(FILES).entrySet()) {
            write(dir.resolve(file.getKey()), file.getValue());
        }
        FolderPublication.writeManifest(dir);
        return dir;
    }

    private static Path write(Path file, String content) throws IOException {
        try (OutputStream out = OutputPublication.createWorkFile(file)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }

    private List<Path> cleanUp(JobExecution current, Set<String> namedElsewhere) {
        return publication.cleanUpFolders(current, PREFIX, namedElsewhere, workRoot,
                id -> FolderPublication.workFolderName("S1", id));
    }

    private static Map<String, Entry> snapshot(Path folder) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            for (Path p : list.toList()) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS);
                String content = a.isRegularFile() ? Files.readString(p, StandardCharsets.US_ASCII)
                        : a.isSymbolicLink() ? "<link>" : "<folder>";
                entries.put(p.getFileName().toString(), new Entry(inode(p), a.lastModifiedTime(), content));
            }
        }
        return entries;
    }

    private static Object inode(Path p) throws IOException {
        return Files.getAttribute(p, "unix:ino", NOFOLLOW_LINKS);
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertPermissions(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);
    }

    @Test
    void onlyUnnamedWorkingFoldersOfTerminalExecutionsOfTheInstanceAreRemoved() throws Exception {
        for (long id : new long[] {3, 4, 5, 6, 10, 11, 12, 13, 20, 99}) {
            work("S1", id);
        }
        work("S2", 4);
        execution(3, BatchStatus.COMPLETED);
        execution(4, BatchStatus.FAILED);
        execution(5, BatchStatus.STOPPED);
        execution(6, BatchStatus.ABANDONED);
        name(execution(10, BatchStatus.FAILED), "S1.10");
        execution(11, BatchStatus.FAILED);
        execution(12, BatchStatus.STARTED);
        execution(13, BatchStatus.UNKNOWN);
        JobExecution current = execution(20, BatchStatus.STARTED);

        List<Path> removed = cleanUp(current, Set.of("S1.11"));

        assertThat(removed).containsExactlyInAnyOrder(workRoot.resolve("S1.3"), workRoot.resolve("S1.4"),
                workRoot.resolve("S1.5"), workRoot.resolve("S1.6"));
        assertThat(names(workRoot)).containsExactly("S1.10", "S1.11", "S1.12", "S1.13", "S1.20", "S1.99", "S2.4");
        assertThat(names(workRoot.resolve("S1.10"))).hasSize(4);
    }

    /** A restart carries its intent in its own context: the folder it names is kept. */
    @Test
    void aFolderThatTheCurrentExecutionsIntentNamesIsKeptAndItsRestartCompletesIt() throws Exception {
        Path dir = work("S1", 4);
        String sha256 = OutputPublication.sha256(dir.resolve(FolderPublication.MANIFEST));
        Path target = kind.resolve("S1");
        assertThatThrownBy(() -> FolderPublication.publishFolder(dir, target, sha256, (step, file) -> {
            if (step == Step.FILE_LINKED) {
                throw new IllegalStateException("injected crash");
            }
        })).isInstanceOf(IllegalStateException.class);
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);
        name(current, "S1.4");

        assertThat(cleanUp(current, Set.of())).isEmpty();

        assertThat(FolderPublication.publishFolder(dir, target, sha256)).isEqualTo(Outcome.PUBLISHED);
        assertThat(names(target)).containsExactly(FolderPublication.CLAIM, "A.txt", "B.txt", "C.txt",
                FolderPublication.MANIFEST);
    }

    /** The working files of a partial publication are second names of published files: those keep their bytes. */
    @Test
    void removingAWorkingFolderKeepsEveryPublishedNameItsBytesAndItsInode() throws Exception {
        Path dir = work("S1", 4);
        String sha256 = OutputPublication.sha256(dir.resolve(FolderPublication.MANIFEST));
        Path target = kind.resolve("S1");
        int[] linked = {0};
        assertThatThrownBy(() -> FolderPublication.publishFolder(dir, target, sha256, (step, file) -> {
            if (step == Step.FILE_LINKED && ++linked[0] == 2) {
                throw new IllegalStateException("injected crash");
            }
        })).isInstanceOf(IllegalStateException.class);
        Map<String, Entry> published = snapshot(target);
        assertThat(published.keySet()).containsExactly(FolderPublication.CLAIM, "A.txt", "B.txt");
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);

        assertThat(cleanUp(current, Set.of())).containsExactly(dir);

        assertThat(Files.exists(dir, NOFOLLOW_LINKS)).isFalse();
        assertThat(snapshot(target)).isEqualTo(published);
        assertThat(Files.readString(target.resolve("A.txt"), StandardCharsets.US_ASCII)).isEqualTo("alpha");
    }

    /** Also a link to an empty folder, where no entry's own check would see the link. */
    @Test
    void aLinkAtAWorkingFoldersNameIsNeitherFollowedNorRemoved() throws Exception {
        Path elsewhere = OutputRoot.ensureDirectory(temp, "elsewhere");
        write(elsewhere.resolve("A.txt"), "alpha");
        Path empty = OutputRoot.ensureDirectory(temp, "empty");
        Path link = Files.createSymbolicLink(workRoot.resolve("S1.4"), elsewhere);
        Path emptyLink = Files.createSymbolicLink(workRoot.resolve("S1.6"), empty);
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);

        assertPermissions(() -> cleanUp(current, Set.of()));
        executions.clear();
        execution(6, BatchStatus.FAILED);
        JobExecution next = execution(7, BatchStatus.STARTED);
        assertPermissions(() -> cleanUp(next, Set.of()));

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.isSymbolicLink(emptyLink)).isTrue();
        assertThat(names(elsewhere)).containsExactly("A.txt");
        assertThat(Files.readString(elsewhere.resolve("A.txt"), StandardCharsets.US_ASCII)).isEqualTo("alpha");
    }

    /** A link, a folder, a wider mode or an odd name inside the folder: the start fails and every entry stays. */
    @Test
    void anUnsafeEntryFailsTheStartAndTheFolderKeepsEveryEntry() throws Exception {
        Path elsewhere = OutputRoot.ensureDirectory(temp, "elsewhere");
        Path behind = write(elsewhere.resolve("X"), "x");
        List<String> cases = List.of("link", "folder", "0644", "0640", "odd name");
        for (int i = 0; i < cases.size(); i++) {
            executions.clear();
            long id = 40 + i;
            Path dir = work("S1", id);
            switch (cases.get(i)) {
                case "link" -> Files.createSymbolicLink(dir.resolve("LINK"), behind);
                case "folder" -> write(OutputRoot.ensureDirectory(dir, "SUB").resolve("F"), "f");
                case "0644" -> Files.setPosixFilePermissions(dir.resolve("B.txt"), PosixFilePermissions.fromString("rw-r--r--"));
                case "0640" -> Files.setPosixFilePermissions(dir.resolve("B.txt"), PosixFilePermissions.fromString("rw-r-----"));
                default -> write(dir.resolve("a b"), "odd");
            }
            Map<String, Entry> before = snapshot(dir);
            execution(id, BatchStatus.FAILED);
            JobExecution current = execution(100 + i, BatchStatus.STARTED);

            assertPermissions(() -> cleanUp(current, Set.of()));

            assertThat(snapshot(dir)).as(cases.get(i)).isEqualTo(before);
        }
        assertThat(Files.readString(behind, StandardCharsets.US_ASCII)).isEqualTo("x");
        assertThat(Files.getPosixFilePermissions(workRoot.resolve("S1.42").resolve("B.txt")))
                .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }

    /** Also an empty one, where no entry's own check would see the folder's mode. */
    @Test
    void aWorkingFolderWithAGroupOrOtherBitFailsTheStartAndKeepsItsModeAndEntries() throws Exception {
        Path dir = work("S1", 4);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"));
        Map<String, Entry> before = snapshot(dir);
        Path empty = FolderPublication.createWorkFolder(workRoot, "S1", 6);
        Files.setPosixFilePermissions(empty, PosixFilePermissions.fromString("rwx---r-x"));
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);

        assertPermissions(() -> cleanUp(current, Set.of()));
        executions.clear();
        execution(6, BatchStatus.FAILED);
        JobExecution next = execution(7, BatchStatus.STARTED);
        assertPermissions(() -> cleanUp(next, Set.of()));

        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(snapshot(dir)).isEqualTo(before);
        assertThat(Files.getPosixFilePermissions(empty)).isEqualTo(PosixFilePermissions.fromString("rwx---r-x"));
        assertThat(names(empty)).isEmpty();
    }

    /** What a recovered owner wrote later, listed or not, is debris of its folder and goes with it. */
    @Test
    void aRecoveredOwnersLaterEntriesAreRemovedWithItsFolder() throws Exception {
        Path dir = work("S1", 4);
        write(dir.resolve("LATE.txt"), "late");
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);

        assertThat(cleanUp(current, Set.of())).containsExactly(dir);

        assertThat(names(workRoot)).isEmpty();
    }

    /** An entry made after the entries were removed and before the folder is: the folder waits for the next start. */
    @Test
    void anEntryThatAppearsBeforeTheFolderIsRemovedLeavesItForTheNextStartWithAWarning(CapturedOutput output)
            throws Exception {
        Path dir = work("S1", 4);
        Map<String, Entry> beforeLate = new TreeMap<>();
        boolean removed = FolderPublication.removeWorkFolder(workRoot, "S1.4", (step, name) -> {
            assertThat(step).isEqualTo(Step.CLEANUP_ENTRIES_REMOVED);
            assertThat(name).isEqualTo("S1.4");
            try {
                assertThat(names(dir)).isEmpty();
                write(dir.resolve("LATE.txt"), "late");
                beforeLate.putAll(snapshot(dir));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(removed).isFalse();
        assertThat(snapshot(dir)).isEqualTo(beforeLate).containsOnlyKeys("LATE.txt");
        assertThat(output).contains("CLEANUP_ENTRIES_LATE: a working folder gained 1 entries during its clean-up")
                .doesNotContain("LATE.txt").doesNotContain(dir.toString()).doesNotContain(temp.toString());

        execution(4, BatchStatus.FAILED);
        JobExecution next = execution(5, BatchStatus.STARTED);
        assertThat(cleanUp(next, Set.of())).containsExactly(dir);
        assertThat(names(workRoot)).isEmpty();
    }

    /** The late entries' names can be sensitive values: the warning gives their count, never a name. */
    @Test
    void aLateEntryWarningNamesNoEntry(CapturedOutput output) throws Exception {
        Path dir = work("S1", 4);
        boolean removed = FolderPublication.removeWorkFolder(workRoot, "S1.4", (step, name) -> {
            try {
                for (String late : SENSITIVE_NAMES) {
                    write(dir.resolve(late), "late");
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(removed).isFalse();
        assertThat(names(dir)).containsExactlyInAnyOrderElementsOf(SENSITIVE_NAMES);
        assertThat(output).contains("CLEANUP_ENTRIES_LATE: a working folder gained " + SENSITIVE_NAMES.size()
                + " entries during its clean-up").doesNotContain(dir.toString()).doesNotContain(temp.toString());
        for (String late : SENSITIVE_NAMES) {
            assertThat(output).as(late).doesNotContain(late);
        }
    }

    @Test
    void aMissingWorkingRootOrFolderIsNoErrorAndNothingIsCreated() throws Exception {
        Path exports = OutputRoot.ensureDirectory(temp, "exports");
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(5, BatchStatus.STARTED);

        assertThat(publication.cleanUpFolders(current, PREFIX, Set.of(), exports.resolve(".work"),
                id -> FolderPublication.workFolderName("E1", id))).isEmpty();
        assertThat(cleanUp(current, Set.of())).isEmpty();

        assertThat(names(exports)).isEmpty();
        assertThat(names(workRoot)).isEmpty();
    }
}
