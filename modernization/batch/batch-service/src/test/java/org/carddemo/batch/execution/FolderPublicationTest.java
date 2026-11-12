package org.carddemo.batch.execution;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.carddemo.batch.execution.FolderPublication.Hook;
import org.carddemo.batch.execution.FolderPublication.Step;
import org.carddemo.batch.execution.OutputPublication.Outcome;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Section 4.8's folder publication on the real file system, without a database: the claim, the
 * links, the manifest last, the verification and the removal of the working folder, with crashes
 * at every step, concurrent publishers and forced interleavings. A path that was there before a
 * publication keeps its bytes, inode and modification time.
 */
@ExtendWith(OutputCaptureExtension.class)
class FolderPublicationTest {

    static final Map<String, String> FILES = Map.of("A.txt", "alpha", "B.txt", "bravo", "C.txt", "charlie");
    /** Another output for the same target: B.txt shared with other bytes, D.txt only here. */
    static final Map<String, String> OTHER = Map.of("B.txt", "other bravo", "D.txt", "delta");

    @TempDir
    Path temp;

    Path kind;
    Path workRoot;

    record Work(Path dir, String sha256, Map<String, String> files) {
    }

    record Entry(Object inode, FileTime modified, String content) {
    }

    static final class Crash extends RuntimeException {
        Crash() {
            super("injected crash", null, false, false);
        }
    }

    @BeforeEach
    void roots() {
        kind = OutputRoot.ensureDirectory(temp, "statements");
        workRoot = OutputRoot.ensureDirectory(kind, ".work");
    }

    private Work work(String id, long execution, Map<String, String> files) throws IOException {
        Path dir = FolderPublication.createWorkFolder(workRoot, id, execution);
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            write(dir.resolve(file.getKey()), file.getValue());
        }
        return new Work(dir, FolderPublication.writeManifest(dir), files);
    }

    private static Path write(Path file, String content) throws IOException {
        try (OutputStream out = OutputPublication.createWorkFile(file)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return file;
    }

    private static String sha(String content) {
        return OutputPublication.sha256(content.getBytes(StandardCharsets.US_ASCII));
    }

    /** Every entry of a folder with its inode, modification time and content (or its kind). */
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

    private static void assertComplete(Path target, Work work) throws IOException {
        List<String> expected = new ArrayList<>(new TreeMap<>(work.files()).keySet());
        expected.add(FolderPublication.CLAIM);
        expected.add(FolderPublication.MANIFEST);
        assertThat(names(target)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(Files.getPosixFilePermissions(target, NOFOLLOW_LINKS))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        for (Map.Entry<String, String> file : work.files().entrySet()) {
            Path p = target.resolve(file.getKey());
            assertThat(Files.readString(p, StandardCharsets.US_ASCII)).isEqualTo(file.getValue());
            assertThat(Files.getPosixFilePermissions(p, NOFOLLOW_LINKS))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        Path manifest = target.resolve(FolderPublication.MANIFEST);
        assertThat(OutputPublication.sha256(manifest)).isEqualTo(work.sha256());
        assertThat(inode(target.resolve(FolderPublication.CLAIM))).isEqualTo(inode(manifest));
    }

    private static void assertMismatch(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");
    }

    private static void assertPermissions(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);
    }

    private static Hook crashAt(Step step, int occurrence) {
        int[] seen = {0};
        return (s, name) -> {
            if (s == step && ++seen[0] == occurrence) {
                throw new Crash();
            }
        };
    }

    private static Outcome publish(Work work, Path target) {
        return FolderPublication.publishFolder(work.dir(), target, work.sha256());
    }

    // ---- single publisher ----

    @Test
    void aFirstPublishClaimsLinksTheFilesAndTheManifestLastAndRemovesTheWorkingFolder() throws Exception {
        Work w = work("S1", 7, FILES);
        Path target = kind.resolve("S1");
        List<String> order = new ArrayList<>();

        assertThat(FolderPublication.publishFolder(w.dir(), target, w.sha256(), (step, name) -> {
            if (step == Step.CLAIMED || step == Step.FILE_LINKED || step == Step.MANIFEST_LINKED) {
                order.add(step == Step.FILE_LINKED ? name : step.name());
            }
        })).isEqualTo(Outcome.PUBLISHED);

        assertComplete(target, w);
        assertThat(order).containsExactly("CLAIMED", "A.txt", "B.txt", "C.txt", "MANIFEST_LINKED");
        assertThat(Files.readString(target.resolve(FolderPublication.MANIFEST), StandardCharsets.US_ASCII))
                .isEqualTo(sha("alpha") + "  A.txt\n" + sha("bravo") + "  B.txt\n" + sha("charlie") + "  C.txt\n");
        assertThat(w.dir()).doesNotExist();
        assertThat(names(workRoot)).isEmpty();
    }

    /** Step 7: unlisted entries keep the working folder; the warning gives their count, never a name. */
    @Test
    void aWorkingFolderThatGainsEntriesIsKeptAndItsWarningNamesNoEntry(CapturedOutput output) throws Exception {
        Work w = work("S1", 7, FILES);
        Path target = kind.resolve("S1");

        assertThat(FolderPublication.publishFolder(w.dir(), target, w.sha256(), (step, name) -> {
            if (step == Step.WORK_REMOVED && name.equals("A.txt")) {
                try {
                    for (String extra : FolderCleanUpTest.SENSITIVE_NAMES) {
                        write(w.dir().resolve(extra), "extra");
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        })).isEqualTo(Outcome.PUBLISHED);

        assertComplete(target, w);
        assertThat(names(w.dir())).containsExactlyInAnyOrderElementsOf(FolderCleanUpTest.SENSITIVE_NAMES);
        assertThat(output).contains("WORK_FOLDER_KEPT: a working folder keeps "
                + FolderCleanUpTest.SENSITIVE_NAMES.size() + " entries that its manifest does not list")
                .doesNotContain(w.dir().toString()).doesNotContain(temp.toString());
        for (String extra : FolderCleanUpTest.SENSITIVE_NAMES) {
            assertThat(output).as(extra).doesNotContain(extra);
        }
    }

    @Test
    void aCompleteFolderWithTheSameManifestIsAlreadyPublishedAndUnchanged() throws Exception {
        Path target = kind.resolve("S1");
        publish(work("S1", 7, FILES), target);
        Map<String, Entry> before = snapshot(target);
        Object folder = inode(target);
        Work again = work("S1", 8, FILES);

        assertThat(publish(again, target)).isEqualTo(Outcome.ALREADY_PUBLISHED);

        assertThat(snapshot(target)).isEqualTo(before);
        assertThat(inode(target)).isEqualTo(folder);
        assertThat(again.dir()).doesNotExist();
    }

    @Test
    void aCompleteFolderWithAnotherManifestIsAMismatchAndBothAreKept() throws Exception {
        Path target = kind.resolve("S1");
        publish(work("S1", 7, FILES), target);
        Map<String, Entry> before = snapshot(target);
        Work other = work("S1", 8, OTHER);
        Map<String, Entry> otherBefore = snapshot(other.dir());

        assertMismatch(() -> publish(other, target));

        assertThat(snapshot(target)).isEqualTo(before);
        assertThat(snapshot(other.dir())).isEqualTo(otherBefore);
    }

    @Test
    void anotherIntentsClaimAloneOrWithSomeOfItsFilesIsAMismatchAndNothingIsLinked() throws Exception {
        for (int linked = 0; linked <= 1; linked++) {
            String id = "P" + linked;
            Path target = kind.resolve(id);
            Work first = work(id, 7, OTHER);
            Hook crash = linked == 0 ? crashAt(Step.CLAIMED, 1) : crashAt(Step.FILE_LINKED, 1);
            assertThatThrownBy(() -> FolderPublication.publishFolder(first.dir(), target, first.sha256(), crash))
                    .isInstanceOf(Crash.class);
            Map<String, Entry> before = snapshot(target);
            assertThat(before).containsKey(FolderPublication.CLAIM).hasSize(1 + linked);
            Work mine = work(id, 8, FILES);
            Map<String, Entry> mineBefore = snapshot(mine.dir());

            assertMismatch(() -> publish(mine, target));

            assertThat(snapshot(target)).as("linked %d", linked).isEqualTo(before);
            assertThat(snapshot(mine.dir())).isEqualTo(mineBefore);
        }
    }

    @Test
    void aPartialFolderOfTheSameIntentIsCompletedAndItsFilesKeepTheirInodes() throws Exception {
        Path target = kind.resolve("S1");
        Work first = work("S1", 7, FILES);
        assertThatThrownBy(() -> FolderPublication.publishFolder(first.dir(), target, first.sha256(),
                crashAt(Step.FILE_LINKED, 1))).isInstanceOf(Crash.class);
        Map<String, Entry> partial = snapshot(target);
        assertThat(partial.keySet()).containsExactly(FolderPublication.CLAIM, "A.txt");
        Work second = work("S1", 8, FILES);

        assertThat(publish(second, target)).isEqualTo(Outcome.PUBLISHED);

        assertComplete(target, second);
        Map<String, Entry> after = snapshot(target);
        assertThat(after.get("A.txt")).isEqualTo(partial.get("A.txt"));
        assertThat(after.get(FolderPublication.CLAIM)).isEqualTo(partial.get(FolderPublication.CLAIM));
    }

    @Test
    void aListedNameWithOtherBytesIsAMismatchKeptAndNoManifestIsLinked() throws Exception {
        Path target = kind.resolve("S1");
        Work w = work("S1", 7, FILES);
        assertThatThrownBy(() -> FolderPublication.publishFolder(w.dir(), target, w.sha256(),
                crashAt(Step.CLAIMED, 1))).isInstanceOf(Crash.class);
        write(target.resolve("A.txt"), "not alpha");
        Map<String, Entry> before = snapshot(target);

        assertMismatch(() -> publish(w, target));

        assertThat(snapshot(target)).isEqualTo(before);
        assertThat(names(target)).doesNotContain(FolderPublication.MANIFEST);
        assertThat(names(w.dir())).contains("A.txt", FolderPublication.MANIFEST);
    }

    @Test
    void aLinkOrAFolderAtTheClaimIsRefusedAndUntouched() throws Exception {
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "x");
        Path linked = OutputRoot.ensureDirectory(kind, "L1");
        Files.createSymbolicLink(linked.resolve(FolderPublication.CLAIM), elsewhere);
        Path folder = OutputRoot.ensureDirectory(kind, "L2");
        OutputRoot.ensureDirectory(folder, FolderPublication.CLAIM);

        for (Path target : List.of(linked, folder)) {
            Map<String, Entry> before = snapshot(target);
            Work w = work(target.getFileName().toString(), 7, FILES);

            assertPermissions(() -> publish(w, target));

            assertThat(snapshot(target)).isEqualTo(before);
            assertThat(names(w.dir())).hasSize(4);
        }
        assertThat(Files.readString(elsewhere)).isEqualTo("x");
    }

    @Test
    void anExtraFileFolderOrLinkInTheFinalFolderIsRefusedAndNothingIsRemovedOrLinked() throws Exception {
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("X"), "x");
        Path withFile = OutputRoot.ensureDirectory(kind, "E1");
        write(withFile.resolve("EXTRA.txt"), "extra");
        Path withFolder = OutputRoot.ensureDirectory(kind, "E2");
        OutputRoot.ensureDirectory(withFolder, "SUB");
        Path withLink = OutputRoot.ensureDirectory(kind, "E3");
        Files.createSymbolicLink(withLink.resolve("LINK"), elsewhere);

        for (Path target : List.of(withFile, withFolder, withLink)) {
            Map<String, Entry> before = snapshot(target);
            Work w = work(target.getFileName().toString(), 7, FILES);

            assertMismatch(() -> publish(w, target));

            assertThat(snapshot(target)).isEqualTo(before);
        }
    }

    /** An entry that appears after the claim: step 6 refuses the folder, and nothing is removed. */
    @Test
    void anExtraFileThatAppearsAfterTheClaimFailsTheVerificationAndIsKept() throws Exception {
        Path target = kind.resolve("S1");
        Work w = work("S1", 7, FILES);

        assertMismatch(() -> FolderPublication.publishFolder(w.dir(), target, w.sha256(), (step, name) -> {
            if (step == Step.CLAIMED) {
                try {
                    write(target.resolve("EXTRA.txt"), "extra");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }));

        assertThat(names(target)).containsExactly(FolderPublication.CLAIM, "A.txt", "B.txt", "C.txt", "EXTRA.txt",
                FolderPublication.MANIFEST);
        assertThat(Files.readString(target.resolve("EXTRA.txt"))).isEqualTo("extra");
        assertThat(names(w.dir())).hasSize(4);
    }

    @Test
    void anEmptyFolderMadeInAdvanceIsJoinedWhenOwnerOnlyAndRefusedOtherwise() throws Exception {
        Path ready = OutputRoot.ensureDirectory(kind, "S1");
        Object inode = inode(ready);
        Work w = work("S1", 7, FILES);

        assertThat(publish(w, ready)).isEqualTo(Outcome.PUBLISHED);

        assertComplete(ready, w);
        assertThat(inode(ready)).isEqualTo(inode);

        Path open = Files.createDirectory(kind.resolve("S2"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
        Work w2 = work("S2", 7, FILES);

        assertPermissions(() -> publish(w2, open));

        assertThat(Files.getPosixFilePermissions(open)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(names(open)).isEmpty();
    }

    @Test
    void aFileOrALinkAtTheFinalFoldersNameIsRefusedAndUntouched() throws Exception {
        Path file = write(kind.resolve("F1"), "file");
        Object fileInode = inode(file);
        Path elsewhere = OutputRoot.ensureDirectory(temp, "elsewhere");
        Path link = Files.createSymbolicLink(kind.resolve("F2"), elsewhere);

        Work w1 = work("F1", 7, FILES);
        assertPermissions(() -> publish(w1, file));
        Work w2 = work("F2", 7, FILES);
        assertPermissions(() -> publish(w2, link));

        assertThat(Files.readString(file)).isEqualTo("file");
        assertThat(inode(file)).isEqualTo(fileInode);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(names(elsewhere)).isEmpty();
    }

    @Test
    void aWorkingFileChangedAfterTheIntentIsAMismatchAndNotLinked() throws Exception {
        Path target = kind.resolve("S1");
        Work w = work("S1", 7, FILES);
        Files.writeString(w.dir().resolve("A.txt"), "changed", StandardOpenOption.TRUNCATE_EXISTING);

        assertMismatch(() -> publish(w, target));

        assertThat(names(target)).containsExactly(FolderPublication.CLAIM);
        assertThat(Files.readString(w.dir().resolve("A.txt"))).isEqualTo("changed");
    }

    @Test
    void aWorkingManifestChangedAfterTheIntentIsAMismatchBeforeTheFinalFolderIsMade() throws Exception {
        Path target = kind.resolve("S1");
        Work w = work("S1", 7, FILES);
        Files.writeString(w.dir().resolve(FolderPublication.MANIFEST), sha("delta") + "  D.txt\n",
                StandardOpenOption.APPEND);

        assertMismatch(() -> publish(w, target));

        assertThat(target).doesNotExist();
    }

    @Test
    void neitherAWorkingManifestNorAClaimIsAMismatch() {
        Path target = kind.resolve("S9");

        assertMismatch(() -> FolderPublication.publishFolder(workRoot.resolve("S9.7"), target, sha("x")));

        assertThat(target).doesNotExist();
    }

    @Test
    void anEntryTheManifestDoesNotListFailsBeforeTheFinalFolderIsMade() throws Exception {
        Work withFile = work("U1", 7, FILES);
        write(withFile.dir().resolve("EXTRA.txt"), "extra");
        Work withFolder = work("U2", 7, FILES);
        OutputRoot.ensureDirectory(withFolder.dir(), "SUB");

        assertMismatch(() -> publish(withFile, kind.resolve("U1")));
        assertMismatch(() -> publish(withFolder, kind.resolve("U2")));

        assertThat(kind.resolve("U1")).doesNotExist();
        assertThat(kind.resolve("U2")).doesNotExist();
        assertThat(Files.readString(withFile.dir().resolve("EXTRA.txt"))).isEqualTo("extra");
    }

    @Test
    void aWorkingFolderIsCreatedNewAndOwnerOnly() throws Exception {
        Path dir = FolderPublication.createWorkFolder(workRoot, "S1", 7);

        assertThat(dir.getFileName().toString()).isEqualTo("S1.7");
        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        write(dir.resolve("A.txt"), "alpha");
        assertMismatch(() -> FolderPublication.createWorkFolder(workRoot, "S1", 7));
        assertThat(names(dir)).containsExactly("A.txt");
    }

    @Test
    void theManifestRefusesANameThatCannotBePublished() throws Exception {
        Path hidden = FolderPublication.createWorkFolder(workRoot, "H1", 7);
        write(hidden.resolve(".hidden"), "x");

        assertMismatch(() -> FolderPublication.writeManifest(hidden));

        assertThat(names(hidden)).containsExactly(".hidden");
    }

    @Test
    void aManifestOutsideItsFormatIsAMismatch() {
        String a = sha("a") + "  A.txt\n";
        String b = sha("b") + "  B.txt\n";
        assertThat(FolderPublication.parse(new byte[0])).isEmpty();
        assertThat(FolderPublication.parse((a + b).getBytes(StandardCharsets.US_ASCII)).keySet())
                .containsExactly("A.txt", "B.txt");
        for (String bad : List.of(b + a, a + a, a.replace("\n", ""), a.replace("\n", "\r\n"),
                a.replace("  ", " "), a.toUpperCase().replace("A.TXT", "A.txt"), sha("m") + "  MANIFEST.sha256\n",
                sha("c") + "  .claim\n", sha("s") + "  sub/x\n", "\n")) {
            assertMismatch(() -> FolderPublication.parse(bad.getBytes(StandardCharsets.US_ASCII)));
        }
    }

    // ---- crashes ----

    /** A failure after each step; the restart ends with the same final folder. */
    @Test
    void aRestartAfterACrashAtAnyStepEndsWithTheSameFinalFolder() throws Exception {
        record Point(Step step, int occurrence) {
        }
        List<Point> points = List.of(new Point(Step.FOLDER_READY, 1), new Point(Step.CLAIMED, 1),
                new Point(Step.FILE_LINKED, 1), new Point(Step.FILE_LINKED, 2), new Point(Step.FILE_LINKED, 3),
                new Point(Step.MANIFEST_LINKED, 1), new Point(Step.VERIFIED, 1), new Point(Step.WORK_REMOVED, 1),
                new Point(Step.WORK_REMOVED, 3), new Point(Step.WORK_REMOVED, 4));
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            String id = "K" + i;
            Path target = kind.resolve(id);
            Work w = work(id, 7, FILES);
            assertThatThrownBy(() -> FolderPublication.publishFolder(w.dir(), target, w.sha256(),
                    crashAt(point.step(), point.occurrence()))).isInstanceOf(Crash.class);
            Map<String, Entry> before = snapshot(target);
            boolean manifestThere = before.containsKey(FolderPublication.MANIFEST);

            assertThat(publish(w, target)).as("%s", point)
                    .isEqualTo(manifestThere ? Outcome.ALREADY_PUBLISHED : Outcome.PUBLISHED);

            assertComplete(target, w);
            Map<String, Entry> after = snapshot(target);
            before.forEach((name, entry) -> assertThat(after.get(name)).as("%s %s", point, name).isEqualTo(entry));
            assertThat(w.dir()).as("%s", point).doesNotExist();
        }
    }

    // ---- concurrency ----

    private static Callable<Object> publisher(CyclicBarrier start, Work work, Path target) {
        return () -> {
            start.await();
            try {
                return publish(work, target);
            } catch (PublicationException e) {
                return e;
            }
        };
    }

    /** A restart and an execution that lost its lease publish one intent, from one working folder, at once. */
    @Test
    void twoPublishersOfOneIntentEndWithOneCompleteFolderAndBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                Path target = kind.resolve("C" + round);
                Work w = work("C" + round, 1, FILES);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Object> a = pool.submit(publisher(start, w, target));
                Future<Object> b = pool.submit(publisher(start, w, target));

                assertThat(List.of(a.get(), b.get())).as("round %d", round)
                        .containsExactlyInAnyOrder(Outcome.PUBLISHED, Outcome.ALREADY_PUBLISHED);
                assertComplete(target, w);
                assertThat(w.dir()).doesNotExist();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoPublishersOfDifferentManifestsEndWithTheWinnersFolderAndTheLoserLinksNothing() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                Path target = kind.resolve("D" + round);
                Work a = work("D" + round, 1, FILES);
                Work b = work("D" + round, 2, OTHER);
                Map<String, Entry> aBefore = snapshot(a.dir());
                Map<String, Entry> bBefore = snapshot(b.dir());
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Object> fa = pool.submit(publisher(start, a, target));
                Future<Object> fb = pool.submit(publisher(start, b, target));
                List<Object> outcomes = List.of(fa.get(), fb.get());

                assertThat(outcomes).as("round %d", round).containsOnlyOnce(Outcome.PUBLISHED);
                assertThat(outcomes).filteredOn(PublicationException.class::isInstance).hasSize(1);
                boolean aWon = outcomes.get(0) == Outcome.PUBLISHED;
                Work winner = aWon ? a : b;
                Work loser = aWon ? b : a;
                assertComplete(target, winner);
                assertThat(winner.dir()).doesNotExist();
                assertThat(snapshot(loser.dir())).as("round %d: the loser's working folder", round)
                        .isEqualTo(aWon ? bBefore : aBefore);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B creates the final folder and finds no manifest; A claims, completes and verifies before B
     * resumes. B fails at its claim, and A's folder keeps every entry as it was.
     */
    @Test
    void aPublisherOfAnotherManifestThatResumesAfterTheWinnerCompletedFailsAtItsClaim() throws Exception {
        Path target = kind.resolve("S1");
        Work a = work("S1", 1, FILES);
        Work b = work("S1", 2, OTHER);
        Map<String, Entry> bBefore = snapshot(b.dir());
        List<Map<String, Entry>> afterA = new ArrayList<>();

        assertMismatch(() -> FolderPublication.publishFolder(b.dir(), target, b.sha256(), (step, name) -> {
            if (step == Step.FOLDER_READY && afterA.isEmpty()) {
                try {
                    assertThat(names(target)).isEmpty();
                    assertThat(publish(a, target)).isEqualTo(Outcome.PUBLISHED);
                    afterA.add(snapshot(target));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }));

        assertThat(afterA).hasSize(1);
        assertThat(snapshot(target)).isEqualTo(afterA.getFirst());
        assertComplete(target, a);
        assertThat(snapshot(b.dir())).isEqualTo(bBefore);
    }

    /** The points of B's run at which A, of the same intent, completes and removes the working folder. */
    static final List<Step> SAME_INTENT_POINTS = List.of(Step.MANIFEST_READ, Step.LISTED_ENTRY, Step.FOLDER_READY,
            Step.FILE_OPENING, Step.FILE_HASHED);

    /** Runs B with A's whole publication at the first {@code at}, then {@code after} on the final folder. */
    private Object sameIntentInterleaving(Work w, Path target, Step at, IoAction after,
            List<Map<String, Entry>> beforeB) {
        try {
            return FolderPublication.publishFolder(w.dir(), target, w.sha256(), (step, name) -> {
                if (step == at && beforeB.isEmpty()) {
                    try {
                        assertThat(publish(w, target)).isEqualTo(Outcome.PUBLISHED);
                        assertThat(w.dir()).doesNotExist();
                        after.run();
                        beforeB.add(snapshot(target));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            });
        } catch (PublicationException e) {
            return e;
        }
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    @Test
    void aPublisherOfTheSameIntentWhoseWorkingFolderVanishesBetweenItsStepsSucceedsAndLinksNothing()
            throws Exception {
        for (Step at : SAME_INTENT_POINTS) {
            String id = "V" + at.ordinal();
            Path target = kind.resolve(id);
            Work w = work(id, 1, FILES);
            List<Map<String, Entry>> beforeB = new ArrayList<>();

            assertThat(sameIntentInterleaving(w, target, at, () -> { }, beforeB)).as("%s", at)
                    .isEqualTo(Outcome.ALREADY_PUBLISHED);

            assertThat(beforeB).as("%s", at).hasSize(1);
            assertThat(snapshot(target)).as("%s", at).isEqualTo(beforeB.getFirst());
            assertComplete(target, w);
        }
    }

    @Test
    void theSameInterleavingsWithTheClaimRemovedBeforeBReadsItAreAMismatch() throws Exception {
        for (Step at : List.of(Step.MANIFEST_READ, Step.LISTED_ENTRY, Step.FOLDER_READY)) {
            String id = "M" + at.ordinal();
            Path target = kind.resolve(id);
            Work w = work(id, 1, FILES);
            List<Map<String, Entry>> beforeB = new ArrayList<>();

            assertThat(sameIntentInterleaving(w, target, at,
                    () -> Files.delete(target.resolve(FolderPublication.CLAIM)), beforeB)).as("%s", at)
                    .isInstanceOf(PublicationException.class);

            assertThat(snapshot(target)).as("%s", at).isEqualTo(beforeB.getFirst());
            assertThat(names(target)).doesNotContain(FolderPublication.CLAIM);
        }
    }

    @Test
    void theSameInterleavingsWithAPayloadFileRemovedBeforeBReadsItAreAMismatch() throws Exception {
        for (Step at : SAME_INTENT_POINTS) {
            String id = "Q" + at.ordinal();
            Path target = kind.resolve(id);
            Work w = work(id, 1, FILES);
            List<Map<String, Entry>> beforeB = new ArrayList<>();

            assertThat(sameIntentInterleaving(w, target, at, () -> Files.delete(target.resolve("A.txt")), beforeB))
                    .as("%s", at).isInstanceOf(PublicationException.class);

            assertThat(snapshot(target)).as("%s", at).isEqualTo(beforeB.getFirst());
            assertThat(names(target)).doesNotContain("A.txt");
        }
    }

    /** The listing itself: the folder and its remaining entries vanish after the first entry is read. */
    @Test
    void aWorkingFolderRemovedDuringTheListingIsNoError() throws Exception {
        Path target = kind.resolve("S1");
        Work w = work("S1", 1, FILES);
        List<String> listed = new ArrayList<>();
        List<Map<String, Entry>> afterA = new ArrayList<>();

        assertThat(FolderPublication.publishFolder(w.dir(), target, w.sha256(), (step, name) -> {
            if (step == Step.LISTED_ENTRY) {
                listed.add(name);
                if (afterA.isEmpty()) {
                    try {
                        publish(w, target);
                        afterA.add(snapshot(target));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
        })).isEqualTo(Outcome.ALREADY_PUBLISHED);

        assertThat(listed).isNotEmpty();
        assertThat(w.dir()).doesNotExist();
        assertThat(snapshot(target)).isEqualTo(afterA.getFirst());
    }

    // ---- unsafe paths: every one is OUTPUT_PERMISSIONS, and nothing that was there changes ----

    /** What may sit at a name instead of a managed regular file; each fails the path checks. */
    enum Unsafe { LINK, FOLDER, MODE_0644, MODE_0640 }

    /** Puts {@code unsafe} at {@code folder/name}; a link points at a file elsewhere with {@code content}. */
    private Path plant(Path folder, String name, String content, Unsafe unsafe) throws IOException {
        Path p = folder.resolve(name);
        switch (unsafe) {
            case LINK -> Files.createSymbolicLink(p, write(OutputRoot.ensureDirectory(temp,
                    "elsewhere-" + folder.getFileName() + "-" + name.replace('.', '_')).resolve(name), content));
            case FOLDER -> OutputRoot.ensureDirectory(folder, name);
            case MODE_0644 -> Files.setPosixFilePermissions(write(p, content), PosixFilePermissions.fromString("rw-r--r--"));
            case MODE_0640 -> Files.setPosixFilePermissions(write(p, content), PosixFilePermissions.fromString("rw-r-----"));
        }
        return p;
    }

    /** The mode of every entry, read without following a link. */
    private static Map<String, String> modes(Path folder) throws IOException {
        Map<String, String> modes = new TreeMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            for (Path p : list.toList()) {
                modes.put(p.getFileName().toString(),
                        PosixFilePermissions.toString(Files.getPosixFilePermissions(p, NOFOLLOW_LINKS)));
            }
        }
        return modes;
    }

    /** Step 4: a listed name that is there but unsafe is refused, even with the right bytes behind it. */
    @Test
    void aLinkAFolderOrAWiderModeAtAListedFinalNameIsRefusedAndEverythingIsKept() throws Exception {
        for (Unsafe unsafe : Unsafe.values()) {
            String id = "P" + unsafe.ordinal();
            Path target = OutputRoot.ensureDirectory(kind, id);
            Path planted = plant(target, "B.txt", "bravo", unsafe);
            Map<String, Entry> before = snapshot(target);
            Map<String, String> modesBefore = modes(target);
            Work w = work(id, 7, FILES);
            Map<String, Entry> workBefore = snapshot(w.dir());

            assertPermissions(() -> publish(w, target));

            Map<String, Entry> after = snapshot(target);
            assertThat(after).as("%s", unsafe).containsAllEntriesOf(before);
            assertThat(after.keySet()).as("%s: linked before B.txt, nothing after", unsafe)
                    .containsExactlyInAnyOrder(FolderPublication.CLAIM, "A.txt", "B.txt");
            assertThat(modes(target)).as("%s", unsafe).containsAllEntriesOf(modesBefore);
            assertThat(snapshot(w.dir())).as("%s: the working folder keeps every entry", unsafe).isEqualTo(workBefore);
            if (unsafe == Unsafe.LINK) {
                Path behind = Files.readSymbolicLink(planted);
                assertThat(Files.readString(behind, StandardCharsets.US_ASCII)).isEqualTo("bravo");
                assertThat(Files.getPosixFilePermissions(behind)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
        }
    }

    /** Step 5: the same at the manifest's name; every file is linked, the manifest is not. */
    @Test
    void aLinkAFolderOrAWiderModeAtTheFinalManifestsNameIsRefusedAndEverythingIsKept() throws Exception {
        for (Unsafe unsafe : Unsafe.values()) {
            String id = "M" + unsafe.ordinal();
            Path target = OutputRoot.ensureDirectory(kind, id);
            Work w = work(id, 7, FILES);
            plant(target, FolderPublication.MANIFEST,
                    Files.readString(w.dir().resolve(FolderPublication.MANIFEST), StandardCharsets.US_ASCII), unsafe);
            Map<String, Entry> before = snapshot(target);
            Map<String, String> modesBefore = modes(target);
            Map<String, Entry> workBefore = snapshot(w.dir());

            assertPermissions(() -> publish(w, target));

            Map<String, Entry> after = snapshot(target);
            assertThat(after).as("%s", unsafe).containsAllEntriesOf(before);
            assertThat(after.keySet()).as("%s", unsafe).containsExactlyInAnyOrder(FolderPublication.CLAIM, "A.txt",
                    "B.txt", "C.txt", FolderPublication.MANIFEST);
            assertThat(modes(target)).as("%s", unsafe).containsAllEntriesOf(modesBefore);
            assertThat(snapshot(w.dir())).as("%s", unsafe).isEqualTo(workBefore);
        }
    }

    /** Step 1: an unsafe listed working file is refused before the final folder exists. */
    @Test
    void aLinkAFolderOrAWiderModeAtAListedWorkingNameIsRefusedBeforeTheFinalFolderIsMade() throws Exception {
        for (Unsafe unsafe : Unsafe.values()) {
            String id = "W" + unsafe.ordinal();
            Work w = work(id, 7, FILES);
            Files.delete(w.dir().resolve("B.txt"));
            plant(w.dir(), "B.txt", "bravo", unsafe);
            Map<String, Entry> workBefore = snapshot(w.dir());
            Map<String, String> modesBefore = modes(w.dir());
            Path target = kind.resolve(id);

            assertPermissions(() -> publish(w, target));

            assertThat(Files.exists(target, NOFOLLOW_LINKS)).as("%s", unsafe).isFalse();
            assertThat(snapshot(w.dir())).as("%s", unsafe).isEqualTo(workBefore);
            assertThat(modes(w.dir())).as("%s", unsafe).isEqualTo(modesBefore);
        }
    }

    /** Step 3: every unsafe kind at the claim's name, with the intent's bytes behind it, is refused. */
    @Test
    void aLinkAFolderOrAWiderModeAtTheClaimsNameIsRefusedAndEverythingIsKept() throws Exception {
        for (Unsafe unsafe : Unsafe.values()) {
            String id = "K" + unsafe.ordinal();
            Path target = OutputRoot.ensureDirectory(kind, id);
            Work w = work(id, 7, FILES);
            plant(target, FolderPublication.CLAIM,
                    Files.readString(w.dir().resolve(FolderPublication.MANIFEST), StandardCharsets.US_ASCII), unsafe);
            Map<String, Entry> before = snapshot(target);
            Map<String, String> modesBefore = modes(target);
            Map<String, Entry> workBefore = snapshot(w.dir());

            assertPermissions(() -> publish(w, target));

            assertThat(snapshot(target)).as("%s", unsafe).isEqualTo(before);
            assertThat(modes(target)).as("%s", unsafe).isEqualTo(modesBefore);
            assertThat(snapshot(w.dir())).as("%s", unsafe).isEqualTo(workBefore);
        }
    }
}
