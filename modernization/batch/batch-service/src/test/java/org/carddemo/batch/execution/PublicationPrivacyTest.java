package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.carddemo.platform.outputroot.OutputRootException.Code.OUTPUT_PERMISSIONS;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.carddemo.batch.execution.FolderPublication.Step;
import org.carddemo.batch.execution.PublicationException.Reason;
import org.carddemo.batch.execution.PublicationFailures.PathReason;
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
 * Section 4.8, Logs: what the publication and the clean-up throw and log names no entry, no file
 * and no path. Each case puts entries with sentinel names (synthetic personal data) where it
 * fails, then checks that the exception, its whole cause and suppressed chain, and the captured
 * log hold the code, the reason and the counts, and neither a sentinel nor any part of the path.
 */
@ExtendWith(OutputCaptureExtension.class)
class PublicationPrivacyTest {

    static final List<String> SENTINELS = FolderCleanUpTest.SENSITIVE_NAMES;
    /** A sentinel that is no output file name and no path segment. */
    static final String ODD_SENTINEL = "123 45 6789";
    static final JobInstance INSTANCE = new JobInstance(3L, "privacyProbe");

    @TempDir
    Path temp;

    Path kind;
    Path workRoot;
    final List<JobExecution> executions = new ArrayList<>();
    OutputPublication publication;

    @BeforeEach
    void roots() {
        kind = OutputRoot.ensureDirectory(temp, "statements");
        workRoot = OutputRoot.ensureDirectory(kind, ".work");
        JobRepository repository = (JobRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJobExecutions" -> List.copyOf(executions);
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

    private static Path write(Path file, String content) throws IOException {
        try (OutputStream out = OutputPublication.createWorkFile(file)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }

    private static void writeUnchecked(Path file, String content) {
        try {
            write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A working folder whose files are named by the sentinels, without its manifest. */
    private Path sentinelWork(long execution) throws IOException {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", execution);
        for (String name : SENTINELS) {
            write(dir.resolve(name), "statement of " + name.length());
        }
        return dir;
    }

    /** The exception and the log hold no sentinel and nothing of the temporary folder's path. */
    private void assertNamesNothing(Throwable thrown, CapturedOutput output) throws IOException {
        List<String> texts = new ArrayList<>();
        collect(thrown, texts, Collections.newSetFromMap(new IdentityHashMap<>()));
        texts.add(output.getAll());
        List<String> forbidden = new ArrayList<>(SENTINELS);
        forbidden.add(ODD_SENTINEL);
        forbidden.add(temp.toString());
        forbidden.add(temp.toRealPath().toString());
        forbidden.add(temp.getFileName().toString());
        for (String text : texts) {
            for (String f : forbidden) {
                assertThat(text).as(f).doesNotContain(f);
            }
        }
    }

    private static void collect(Throwable t, List<String> texts, Set<Throwable> seen) {
        if (t == null || !seen.add(t)) {
            return;
        }
        texts.add(String.valueOf(t.getMessage()));
        texts.add(t.toString());
        collect(t.getCause(), texts, seen);
        for (Throwable suppressed : t.getSuppressed()) {
            collect(suppressed, texts, seen);
        }
    }

    private PublicationException assertMismatch(ThrowingCallable call, Reason reason, CapturedOutput output)
            throws IOException {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PublicationException.class)
                .hasMessageStartingWith("PUBLICATION_MISMATCH: " + reason.name() + ": ").hasNoCause();
        assertThat(((PublicationException) thrown).reason()).isEqualTo(reason);
        assertNamesNothing(thrown, output);
        return (PublicationException) thrown;
    }

    private void assertPermissions(ThrowingCallable call, PathReason reason, CapturedOutput output)
            throws IOException {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(OutputRootException.class)
                .hasMessageStartingWith("OUTPUT_PERMISSIONS: " + reason.name() + ": ").hasNoCause();
        assertThat(((OutputRootException) thrown).code()).isEqualTo(OUTPUT_PERMISSIONS);
        assertNamesNothing(thrown, output);
    }

    // ---- folder publication ----

    @Test
    void aListedWorkingFileChangedAfterItsManifestIsAMismatchThatNamesNoFile(CapturedOutput output)
            throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);
        Files.writeString(dir.resolve(SENTINELS.get(4)), "changed", StandardOpenOption.APPEND);

        assertMismatch(() -> FolderPublication.publishFolder(dir, kind.resolve("S1"), sha256),
                Reason.WORK_SHA256_MISMATCH, output);
    }

    @Test
    void aListedWorkingFileWithAGroupBitIsRefusedNamingNoFile(CapturedOutput output) throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);
        Files.setPosixFilePermissions(dir.resolve(SENTINELS.get(0)), PosixFilePermissions.fromString("rw-r--r--"));

        assertPermissions(() -> FolderPublication.publishFolder(dir, kind.resolve("S1"), sha256),
                PathReason.PATH_REFUSED, output);
    }

    @Test
    void aLinkAtAListedFinalNameIsRefusedNamingNoFile(CapturedOutput output) throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "x");
        Path target = OutputRoot.ensureDirectory(kind, "S1");
        Files.createSymbolicLink(target.resolve(SENTINELS.get(2)), elsewhere);

        assertPermissions(() -> FolderPublication.publishFolder(dir, target, sha256), PathReason.PATH_REFUSED, output);
        assertThat(Files.isSymbolicLink(target.resolve(SENTINELS.get(2)))).isTrue();
    }

    @Test
    void aListedFinalNameWithOtherBytesIsAMismatchNamingNoFile(CapturedOutput output) throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);
        Path target = OutputRoot.ensureDirectory(kind, "S1");
        write(target.resolve(SENTINELS.get(3)), "other bytes");

        assertMismatch(() -> FolderPublication.publishFolder(dir, target, sha256), Reason.FINAL_SHA256_MISMATCH,
                output);
        assertThat(Files.readString(target.resolve(SENTINELS.get(3)), StandardCharsets.US_ASCII))
                .isEqualTo("other bytes");
    }

    @Test
    void aListedNameMissingInBothFoldersIsAMismatchNamingNoFile(CapturedOutput output) throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);

        assertMismatch(() -> FolderPublication.publishFolder(dir, kind.resolve("S1"), sha256, (step, name) -> {
            if (step == Step.FILE_OPENING && SENTINELS.get(1).equals(name)) {
                try {
                    Files.delete(dir.resolve(name));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }), Reason.FINAL_MISSING, output);
    }

    @Test
    void anUnlistedWorkingEntryIsAMismatchNamingNoEntry(CapturedOutput output) throws Exception {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", 7);
        write(dir.resolve("A.txt"), "alpha");
        String sha256 = FolderPublication.writeManifest(dir);
        for (String name : SENTINELS) {
            write(dir.resolve(name), "late");
        }

        assertMismatch(() -> FolderPublication.publishFolder(dir, kind.resolve("S1"), sha256),
                Reason.WORK_ENTRY_UNLISTED, output);
    }

    @Test
    void unlistedFinalEntriesAreAMismatchThatGivesTheirCountOnly(CapturedOutput output) throws Exception {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", 7);
        write(dir.resolve("A.txt"), "alpha");
        String sha256 = FolderPublication.writeManifest(dir);
        Path target = OutputRoot.ensureDirectory(kind, "S1");
        for (String name : SENTINELS) {
            write(target.resolve(name), "other");
        }

        PublicationException e = assertMismatch(() -> FolderPublication.publishFolder(dir, target, sha256),
                Reason.FINAL_ENTRY_UNLISTED, output);
        assertThat(e).hasMessageContaining("holds " + SENTINELS.size() + " entries");
    }

    @Test
    void anEntryThatAppearsBeforeTheVerificationIsAMismatchThatGivesCountsOnly(CapturedOutput output)
            throws Exception {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", 7);
        write(dir.resolve("A.txt"), "alpha");
        String sha256 = FolderPublication.writeManifest(dir);
        Path target = kind.resolve("S1");

        PublicationException e = assertMismatch(() -> FolderPublication.publishFolder(dir, target, sha256,
                (step, name) -> {
                    if (step == Step.MANIFEST_LINKED) {
                        writeUnchecked(target.resolve(SENTINELS.get(5)), "late");
                    }
                }), Reason.FINAL_NOT_EXACT, output);
        assertThat(e).hasMessageContaining("(0 missing, 1 other)");
    }

    @Test
    void anotherOutputsClaimIsAMismatchNamingNoFile(CapturedOutput output) throws Exception {
        Path other = FolderPublication.createWorkFolder(workRoot, "S1", 6);
        for (String name : SENTINELS) {
            write(other.resolve(name), "other output");
        }
        Path target = kind.resolve("S1");
        FolderPublication.publishFolder(other, target, FolderPublication.writeManifest(other));
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);

        assertMismatch(() -> FolderPublication.publishFolder(dir, target, sha256), Reason.CLAIM_OTHER_OUTPUT, output);
    }

    @Test
    void neitherAWorkingManifestNorAClaimIsAMismatchNamingNoPath(CapturedOutput output) throws Exception {
        Path dir = sentinelWork(7);
        String sha256 = FolderPublication.writeManifest(dir);
        Files.delete(dir.resolve(FolderPublication.MANIFEST));

        assertMismatch(() -> FolderPublication.publishFolder(dir, kind.resolve("S1"), sha256),
                Reason.NOTHING_TO_PUBLISH, output);
    }

    @Test
    void aWorkingEntryWithNoOutputFileNameIsRefusedNamingNoEntry(CapturedOutput output) throws Exception {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", 7);
        write(dir.resolve(ODD_SENTINEL), "odd");

        assertMismatch(() -> FolderPublication.writeManifest(dir), Reason.WORK_ENTRY_UNNAMED, output);
    }

    @Test
    void anExistingWorkingFolderIsRefusedNamingNoId(CapturedOutput output) throws Exception {
        FolderPublication.createWorkFolder(workRoot, SENTINELS.get(0), 7);

        assertMismatch(() -> FolderPublication.createWorkFolder(workRoot, SENTINELS.get(0), 7), Reason.WORK_EXISTS,
                output);
    }

    /** A file system failure keeps the type of its cause, which the callers decide by, and nothing else. */
    @Test
    void aFileSystemFailureNamesItsTypeOnly(CapturedOutput output) throws Exception {
        Throwable thrown = catchThrowable(() -> FolderPublication.writeManifest(workRoot.resolve(SENTINELS.get(0))));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasMessage("OUTPUT_IO: NoSuchFileException");
        assertThat(thrown.getCause()).isExactlyInstanceOf(NoSuchFileException.class).hasNoCause();
        assertThat(thrown.getCause().getMessage()).isNull();
        assertNamesNothing(thrown, output);
    }

    /** A failing working-file stream whose exceptions name a path, as the file system's do. */
    private static final class Failing extends OutputStream {

        private final Path named;
        private final Set<String> failing;

        Failing(Path named, String... failing) {
            this.named = named;
            this.failing = Set.of(failing);
        }

        @Override
        public void write(int b) throws IOException {
            fail("write", new FileSystemException(named.toString(), null, "No space left on device"));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            fail("writeArray", new AccessDeniedException(named.toString()));
        }

        @Override
        public void flush() throws IOException {
            fail("flush", new NoSuchFileException(named.toString()));
        }

        @Override
        public void close() throws IOException {
            fail("close", new FileSystemException(named.toString(), named.toString(), "close"));
        }

        private void fail(String operation, IOException e) throws IOException {
            if (failing.contains(operation)) {
                throw e;
            }
        }
    }

    private void assertTypeOnly(ThrowingCallable call, Class<? extends IOException> type, CapturedOutput output)
            throws IOException {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isExactlyInstanceOf(type).hasNoCause();
        assertThat(thrown.getMessage()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
        assertNamesNothing(thrown, output);
    }

    /**
     * A working file's write, flush, force and close: each failure keeps its exception's type and
     * nothing else. When the force fails and the close's flush and close fail too, the close's
     * exception is thrown, without the flush's as suppressed.
     */
    @Test
    void aFailedWriteFlushOrCloseOfAWorkingFileNamesItsTypeOnly(CapturedOutput output) throws Exception {
        Path named = workRoot.resolve(SENTINELS.get(0));
        assertThat(catchThrowable(() -> new Failing(named, "write").write(1)))
                .as("unredacted, the exception names the file").hasMessageContaining(SENTINELS.get(0));
        OutputPublication.Force forced = () -> { };
        OutputPublication.Force refused = () -> {
            throw new AccessDeniedException(named.toString());
        };

        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "write"), forced).write(1),
                FileSystemException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "writeArray"), forced).write(new byte[3]),
                AccessDeniedException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "flush"), forced).flush(),
                NoSuchFileException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "flush"), forced).close(),
                NoSuchFileException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "close"), forced).close(),
                FileSystemException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named), refused).close(),
                AccessDeniedException.class, output);
        assertTypeOnly(() -> OutputPublication.redacting(new Failing(named, "flush", "close"), refused).close(),
                FileSystemException.class, output);

        OutputStream closed = OutputPublication.createWorkFile(workRoot.resolve(SENTINELS.get(1)));
        closed.close();
        assertTypeOnly(() -> closed.write(1), IOException.class, output);
    }

    /** A directory stream's iterator failure keeps the type of the file system's exception only. */
    @Test
    void anIterationFailureNamesItsTypeOnly(CapturedOutput output) throws Exception {
        Path named = workRoot.resolve(SENTINELS.get(0));

        Throwable thrown = catchThrowable(() -> PublicationFailures.redacting(() -> {
            throw new DirectoryIteratorException(new AccessDeniedException(named.toString()));
        }));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasMessage("OUTPUT_IO: AccessDeniedException");
        assertThat(thrown.getCause()).isExactlyInstanceOf(AccessDeniedException.class).hasNoCause();
        assertThat(thrown.getCause().getMessage()).isNull();
        assertNamesNothing(thrown, output);
    }
    // ---- clean-up ----

    @Test
    void aRefusedCleanUpNamesNoEntry(CapturedOutput output) throws Exception {
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "x");
        Path link = FolderPublication.createWorkFolder(workRoot, "S1", 4);
        Files.createSymbolicLink(link.resolve(SENTINELS.get(6)), elsewhere);
        Path wide = FolderPublication.createWorkFolder(workRoot, "S1", 5);
        Files.setPosixFilePermissions(write(wide.resolve(SENTINELS.get(1)), "x"),
                PosixFilePermissions.fromString("rw-r-----"));
        Path odd = FolderPublication.createWorkFolder(workRoot, "S1", 6);
        write(odd.resolve(ODD_SENTINEL), "x");

        long[][] cases = {{4, 14}, {5, 15}};
        for (long[] c : cases) {
            executions.clear();
            execution(c[0], BatchStatus.FAILED);
            JobExecution current = execution(c[1], BatchStatus.STARTED);
            assertPermissions(() -> publication.cleanUpFolders(current, null, Set.of(), workRoot,
                    id -> FolderPublication.workFolderName("S1", id)), PathReason.PATH_REFUSED, output);
        }
        executions.clear();
        execution(6, BatchStatus.FAILED);
        JobExecution current = execution(16, BatchStatus.STARTED);
        assertPermissions(() -> publication.cleanUpFolders(current, null, Set.of(), workRoot,
                id -> FolderPublication.workFolderName("S1", id)), PathReason.ENTRY_NOT_SEGMENT, output);

        assertThat(Files.isSymbolicLink(link.resolve(SENTINELS.get(6)))).isTrue();
        assertThat(wide.resolve(SENTINELS.get(1))).exists();
        assertThat(odd.resolve(ODD_SENTINEL)).exists();
    }

    @Test
    void aRefusedFileCleanUpNamesNoFile(CapturedOutput output) throws Exception {
        Path folder = OutputRoot.ensureDirectory(temp, "reports", "R1");
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "x");
        Files.createSymbolicLink(folder.resolve(OutputPublication.workFileName(SENTINELS.get(4), 4)), elsewhere);
        execution(4, BatchStatus.FAILED);
        JobExecution current = execution(7, BatchStatus.STARTED);

        assertPermissions(() -> publication.cleanUp(current, null, folder,
                id -> OutputPublication.workFileName(SENTINELS.get(4), id)), PathReason.PATH_REFUSED, output);
    }

    // ---- single-file publication ----

    @Test
    void aTargetWithOtherBytesIsAMismatchNamingNoFile(CapturedOutput output) throws Exception {
        Path folder = OutputRoot.ensureDirectory(temp, "reports", "R1");
        Path target = write(folder.resolve(SENTINELS.get(4)), "earlier");
        Path work = write(folder.resolve(OutputPublication.workFileName(SENTINELS.get(4), 7)), "mine");
        String sha256 = OutputPublication.sha256("mine".getBytes(StandardCharsets.US_ASCII));

        assertMismatch(() -> OutputPublication.publishFile(work, target, sha256), Reason.TARGET_SHA256_MISMATCH,
                output);
        assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("earlier");
    }

    @Test
    void aLinkAtTheTargetIsRefusedNamingNoFile(CapturedOutput output) throws Exception {
        Path folder = OutputRoot.ensureDirectory(temp, "reports", "R1");
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "bytes");
        Path target = Files.createSymbolicLink(folder.resolve(SENTINELS.get(4)), elsewhere);
        Path work = write(folder.resolve(OutputPublication.workFileName(SENTINELS.get(4), 7)), "bytes");
        String sha256 = OutputPublication.sha256("bytes".getBytes(StandardCharsets.US_ASCII));

        assertPermissions(() -> OutputPublication.publishFile(work, target, sha256), PathReason.PATH_REFUSED, output);
    }

    @Test
    void neitherTargetNorWorkingFileIsAMismatchNamingNoFile(CapturedOutput output) throws Exception {
        Path folder = OutputRoot.ensureDirectory(temp, "reports", "R1");
        String sha256 = OutputPublication.sha256("bytes".getBytes(StandardCharsets.US_ASCII));

        assertMismatch(() -> OutputPublication.publishFile(
                folder.resolve(OutputPublication.workFileName(SENTINELS.get(4), 7)), folder.resolve(SENTINELS.get(4)),
                sha256), Reason.NOTHING_TO_PUBLISH, output);
    }

    @Test
    void anExistingWorkingFileOrAMissingFileNamesNoFile(CapturedOutput output) throws Exception {
        Path folder = OutputRoot.ensureDirectory(temp, "reports", "R1");
        Path work = write(folder.resolve(SENTINELS.get(2)), "bytes");

        assertMismatch(() -> OutputPublication.createWorkFile(work), Reason.WORK_EXISTS, output);
        assertMismatch(() -> OutputPublication.sha256(folder.resolve(SENTINELS.get(3))), Reason.FILE_MISSING, output);
    }

    // ---- the reasons ----

    @Test
    void aReasonGivesItsCodeItsNameAndItsCountsOnly() {
        PublicationException e = new PublicationException(Reason.FINAL_NOT_EXACT, 2, 3);

        assertThat(e).hasMessage("PUBLICATION_MISMATCH: FINAL_NOT_EXACT: the final folder does not hold exactly its"
                + " manifest's files (2 missing, 3 other); nothing is removed");
        assertThat(e.reason()).isEqualTo(Reason.FINAL_NOT_EXACT);
        assertThat(e.detail()).startsWith("FINAL_NOT_EXACT: ");
        assertThatThrownBy(() -> new PublicationException(Reason.FINAL_NOT_EXACT, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicationException(Reason.CLAIM_MISSING, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PublicationException("PRTCATBL").reason()).isNull();
    }
}
