package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** Section 4.8's single-file publication, intent and clean-up on the real file system, without a database. */
class OutputPublicationTest {

    static final String PREFIX = "carddemo.test";
    static final JobInstance INSTANCE = new JobInstance(3L, "publicationProbe");

    @TempDir
    Path temp;

    Path folder;
    final List<JobExecution> executions = new ArrayList<>();
    final List<Long> contextUpdates = new ArrayList<>();
    OutputPublication publication;

    @BeforeEach
    void folder() {
        folder = OutputRoot.ensureDirectory(temp, "out");
        JobRepository repository = (JobRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJobExecutions" -> {
                        assertThat(args[0]).isEqualTo(INSTANCE);
                        yield List.copyOf(executions);
                    }
                    case "updateExecutionContext" -> {
                        contextUpdates.add(((JobExecution) args[0]).getId());
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        publication = new OutputPublication(repository, null, null);
    }

    @AfterEach
    void noTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    private static JobExecution execution(long id, BatchStatus status) {
        JobExecution execution = new JobExecution(id, INSTANCE, new JobParameters());
        execution.setStatus(status);
        return execution;
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

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII);
    }

    private List<String> names() throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aWorkingFileIsCreatedNewAndOwnerOnlyAndAnExistingOneIsLeftAsItIs() throws Exception {
        Path work = write(folder.resolve(OutputPublication.workFileName("OUT.txt", 7)), "first");

        assertThat(work.getFileName().toString()).isEqualTo("OUT.txt.7.tmp");
        assertThat(Files.getPosixFilePermissions(work)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        assertThatThrownBy(() -> OutputPublication.createWorkFile(work)).isInstanceOf(PublicationException.class)
                .hasMessageStartingWith("PUBLICATION_MISMATCH: ");
        assertThat(read(work)).isEqualTo("first");
    }

    @Test
    void aWorkingFileIsLinkedOntoItsTargetAndThenRemoved() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Path target = folder.resolve("OUT.txt");

        assertThat(OutputPublication.publishFile(work, target, sha("bytes"))).isEqualTo(OutputPublication.Outcome.PUBLISHED);

        assertThat(read(target)).isEqualTo("bytes");
        assertThat(Files.getPosixFilePermissions(target)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void anExistingTargetWithTheSameBytesIsKeptAndTheWorkingFileGoes() throws Exception {
        Path target = OutputPublication.publishFile(write(folder.resolve("OUT.txt.7.tmp"), "bytes"),
                folder.resolve("OUT.txt"), sha("bytes")) == OutputPublication.Outcome.PUBLISHED ? folder.resolve("OUT.txt") : null;
        Object inode = Files.getAttribute(target, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        Path work = write(folder.resolve("OUT.txt.8.tmp"), "bytes");

        assertThat(OutputPublication.publishFile(work, target, sha("bytes")))
                .isEqualTo(OutputPublication.Outcome.ALREADY_PUBLISHED);

        assertThat(Files.getAttribute(target, "unix:ino", LinkOption.NOFOLLOW_LINKS)).isEqualTo(inode);
        assertThat(read(target)).isEqualTo("bytes");
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void anExistingTargetWithOtherBytesIsAMismatchAndNothingIsReplaced() throws Exception {
        Path target = write(folder.resolve("OUT.txt"), "published");
        Path work = write(folder.resolve("OUT.txt.8.tmp"), "other");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("other")))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");

        assertThat(read(target)).isEqualTo("published");
        assertThat(read(work)).isEqualTo("other");
    }

    /** The primitive itself: link(2) fails on an existing name and leaves its bytes. */
    @Test
    void createLinkNeverReplacesAnExistingName() throws Exception {
        Path target = write(folder.resolve("OUT.txt"), "published");
        Path work = write(folder.resolve("OUT.txt.8.tmp"), "other");

        assertThatThrownBy(() -> Files.createLink(target, work)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(read(target)).isEqualTo("published");
    }

    /** Two executions publish onto one target at once: one wins, and the other never changes its bytes. */
    @Test
    void concurrentPublicationsOfOtherBytesLeaveTheWinnersBytes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                Path target = folder.resolve("R" + round + ".txt");
                Path a = write(folder.resolve("R" + round + ".txt.1.tmp"), "a" + round);
                Path b = write(folder.resolve("R" + round + ".txt.2.tmp"), "b" + round);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Object> first = pool.submit(publish(start, a, target, sha("a" + round)));
                Future<Object> second = pool.submit(publish(start, b, target, sha("b" + round)));
                List<Object> outcomes = List.of(first.get(), second.get());

                assertThat(outcomes).as("round %d", round).containsOnlyOnce(OutputPublication.Outcome.PUBLISHED);
                assertThat(outcomes).filteredOn(PublicationException.class::isInstance).hasSize(1);
                String winner = outcomes.get(0) == OutputPublication.Outcome.PUBLISHED ? "a" : "b";
                assertThat(read(target)).isEqualTo(winner + round);
                Path loser = winner.equals("a") ? b : a;
                assertThat(read(loser)).as("the loser's working file is kept").isEqualTo((winner.equals("a") ? "b" : "a") + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPublicationsOfTheSameBytesPublishOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                Path target = folder.resolve("S" + round + ".txt");
                Path a = write(folder.resolve("S" + round + ".txt.1.tmp"), "same");
                Path b = write(folder.resolve("S" + round + ".txt.2.tmp"), "same");
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Object> first = pool.submit(publish(start, a, target, sha("same")));
                Future<Object> second = pool.submit(publish(start, b, target, sha("same")));

                assertThat(List.of(first.get(), second.get())).as("round %d", round).containsExactlyInAnyOrder(
                        OutputPublication.Outcome.PUBLISHED, OutputPublication.Outcome.ALREADY_PUBLISHED);
                assertThat(read(target)).isEqualTo("same");
                assertThat(a).doesNotExist();
                assertThat(b).doesNotExist();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A restart and an execution that lost its lease publish the same committed working file at
     * once: whichever links first publishes, and the other finds the working file gone and the
     * target there, so it reports the earlier publication instead of a mismatch.
     */
    @Test
    void concurrentPublicationsOfOneWorkingFilePublishOnceAndNeverFail() throws Exception {
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int round = 0; round < 100; round++) {
                Path target = folder.resolve("W" + round + ".txt");
                Path work = write(folder.resolve("W" + round + ".txt.1.tmp"), "one" + round);
                CyclicBarrier start = new CyclicBarrier(callers);
                List<Future<Object>> futures = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    futures.add(pool.submit(publish(start, work, target, sha("one" + round))));
                }
                List<Object> outcomes = new ArrayList<>();
                for (Future<Object> future : futures) {
                    outcomes.add(future.get());
                }

                assertThat(outcomes).as("round %d", round).containsOnlyOnce(OutputPublication.Outcome.PUBLISHED)
                        .containsOnly(OutputPublication.Outcome.PUBLISHED, OutputPublication.Outcome.ALREADY_PUBLISHED);
                assertThat(read(target)).isEqualTo("one" + round);
                assertThat(work).doesNotExist();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTargetPublishedEarlierWhoseWorkingFileIsGoneIsAlreadyPublished() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Path target = folder.resolve("OUT.txt");
        OutputPublication.publishFile(work, target, sha("bytes"));

        assertThat(OutputPublication.publishFile(work, target, sha("bytes")))
                .isEqualTo(OutputPublication.Outcome.ALREADY_PUBLISHED);
        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("other")))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void aWorkingFileWithOtherBytesBesideAnEqualTargetIsAMismatchAndKept() throws Exception {
        Path target = write(folder.resolve("OUT.txt"), "bytes");
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "changed");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("bytes")))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");
        assertThat(read(work)).isEqualTo("changed");
    }
    /** Runs a whole other publication of the same working file at {@code at}, once. */
    private static java.util.function.Consumer<OutputPublication.Step> otherPublisherAt(OutputPublication.Step at,
            Path work, Path target, String sha256, List<Object> other) {
        return step -> {
            if (step == at && other.isEmpty()) {
                other.add(OutputPublication.publishFile(work, target, sha256));
            }
        };
    }

    @Test
    void aWorkingFilePublishedByAnotherCallBeforeItIsHashedIsAlreadyPublished() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Path target = folder.resolve("OUT.txt");
        List<Object> other = new ArrayList<>();

        assertThat(OutputPublication.publishFile(work, target, sha("bytes"),
                otherPublisherAt(OutputPublication.Step.STARTED, work, target, sha("bytes"), other)))
                .isEqualTo(OutputPublication.Outcome.ALREADY_PUBLISHED);

        assertThat(other).containsExactly(OutputPublication.Outcome.PUBLISHED);
        assertThat(read(target)).isEqualTo("bytes");
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void aWorkingFilePublishedByAnotherCallBeforeTheLinkIsAlreadyPublished() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Path target = folder.resolve("OUT.txt");
        List<Object> other = new ArrayList<>();

        assertThat(OutputPublication.publishFile(work, target, sha("bytes"),
                otherPublisherAt(OutputPublication.Step.WORK_HASHED, work, target, sha("bytes"), other)))
                .isEqualTo(OutputPublication.Outcome.ALREADY_PUBLISHED);

        assertThat(other).containsExactly(OutputPublication.Outcome.PUBLISHED);
        assertThat(read(target)).isEqualTo("bytes");
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void aWorkingFileRemovedByAnotherCallBesideAPublishedTargetIsAlreadyPublished() throws Exception {
        Path target = write(folder.resolve("OUT.txt"), "bytes");
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        List<Object> other = new ArrayList<>();

        assertThat(OutputPublication.publishFile(work, target, sha("bytes"),
                otherPublisherAt(OutputPublication.Step.TARGET_HASHED, work, target, sha("bytes"), other)))
                .isEqualTo(OutputPublication.Outcome.ALREADY_PUBLISHED);

        assertThat(other).containsExactly(OutputPublication.Outcome.ALREADY_PUBLISHED);
        assertThat(names()).containsExactly("OUT.txt");
    }

    @Test
    void aTargetLinkedFromOtherBytesAfterTheWorkingFileIsHashedIsAMismatchDecidedByTheLink() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "mine");
        Path other = write(folder.resolve("OUT.txt.8.tmp"), "theirs");
        Path target = folder.resolve("OUT.txt");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("mine"), step -> {
            if (step == OutputPublication.Step.WORK_HASHED) {
                OutputPublication.publishFile(other, target, sha("theirs"));
            }
        })).isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");

        assertThat(read(target)).isEqualTo("theirs");
        assertThat(read(work)).isEqualTo("mine");
        assertThat(names()).containsExactly("OUT.txt", "OUT.txt.7.tmp");
    }
    @Test
    void aWorkingFileThatVanishesWithNoTargetPublishesNothing() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Path target = folder.resolve("OUT.txt");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("bytes"), step -> {
            if (step == OutputPublication.Step.STARTED) {
                try {
                    Files.delete(work);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        })).isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ")
                .hasMessageContaining("nothing is published");

        assertThat(names()).isEmpty();
    }
    private static Callable<Object> publish(CyclicBarrier start, Path work, Path target, String sha256) {
        return () -> {
            start.await();
            try {
                return OutputPublication.publishFile(work, target, sha256);
            } catch (PublicationException e) {
                return e;
            }
        };
    }

    @Test
    void aWorkingFileWithOtherBytesThanExpectedIsNotPublished() throws Exception {
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "changed");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, folder.resolve("OUT.txt"), sha("bytes")))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");

        assertThat(names()).containsExactly("OUT.txt.7.tmp");
        assertThat(read(work)).isEqualTo("changed");
    }

    @Test
    void neitherTargetNorWorkingFileIsAMismatch() {
        assertThatThrownBy(() -> OutputPublication.publishFile(folder.resolve("OUT.txt.7.tmp"), folder.resolve("OUT.txt"),
                sha("bytes"))).isInstanceOf(PublicationException.class)
                .hasMessageStartingWith("PUBLICATION_MISMATCH: ").hasMessageContaining("nothing is published");
        assertThat(folder.resolve("OUT.txt")).doesNotExist();
    }

    @Test
    void aLinkAtTheTargetIsRefusedAndNotFollowed() throws Exception {
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("OTHER.txt"), "bytes");
        Path target = Files.createSymbolicLink(folder.resolve("OUT.txt"), elsewhere);
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("bytes")))
                .isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);

        assertThat(Files.isSymbolicLink(target)).isTrue();
        assertThat(read(elsewhere)).isEqualTo("bytes");
        assertThat(read(work)).isEqualTo("bytes");
    }

    /**
     * A folder at a single-file target: link(2) fails on the existing name, the folder is no
     * regular file, so the publication is refused, and the folder, its files and the working file stay.
     */
    @Test
    void aFolderAtTheTargetIsRefusedAndKeptWithItsFiles() throws Exception {
        Path target = OutputRoot.ensureDirectory(folder, "OUT.txt");
        Path inside = write(target.resolve("KEPT.txt"), "kept");
        Object inode = Files.getAttribute(target, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("bytes")))
                .isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);

        assertFolderKept(target, inode, inside);
        assertThat(read(work)).isEqualTo("bytes");
        assertThat(names()).containsExactly("OUT.txt", "OUT.txt.7.tmp");
    }

    @Test
    void aFolderMadeAtTheTargetAfterTheWorkingFileIsHashedIsRefusedByTheLink() throws Exception {
        Path target = folder.resolve("OUT.txt");
        Path work = write(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Object[] inode = new Object[1];
        Path[] inside = new Path[1];

        assertThatThrownBy(() -> OutputPublication.publishFile(work, target, sha("bytes"), step -> {
            if (step == OutputPublication.Step.WORK_HASHED) {
                try {
                    OutputRoot.ensureDirectory(folder, "OUT.txt");
                    inside[0] = write(target.resolve("KEPT.txt"), "kept");
                    inode[0] = Files.getAttribute(target, "unix:ino", LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        })).isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);

        assertFolderKept(target, inode[0], inside[0]);
        assertThat(read(work)).isEqualTo("bytes");
        assertThat(names()).containsExactly("OUT.txt", "OUT.txt.7.tmp");
    }

    private static void assertFolderKept(Path target, Object inode, Path inside) throws IOException {
        assertThat(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.getAttribute(target, "unix:ino", LinkOption.NOFOLLOW_LINKS)).isEqualTo(inode);
        try (Stream<Path> entries = Files.list(target)) {
            assertThat(entries.map(p -> p.getFileName().toString()).toList()).containsExactly("KEPT.txt");
        }
        assertThat(read(inside)).isEqualTo("kept");
    }

    @Test
    void aWorkingFileOthersCanReadIsRefused() throws Exception {
        Path work = Files.writeString(folder.resolve("OUT.txt.7.tmp"), "bytes");
        Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> OutputPublication.publishFile(work, folder.resolve("OUT.txt"), sha("bytes")))
                .isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);
        assertThat(folder.resolve("OUT.txt")).doesNotExist();
        assertThat(read(work)).isEqualTo("bytes");
        assertThat(Files.getPosixFilePermissions(work)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
    }

    @Test
    void anIntentIsReadOnlyWhenBothKeysAreThere() {
        ExecutionContext context = new ExecutionContext();
        assertThat(OutputPublication.intent(context, PREFIX)).isEmpty();

        context.putString(PREFIX + OutputPublication.WORK_PATH, "OUT.txt.7.tmp");
        assertThatThrownBy(() -> OutputPublication.intent(context, PREFIX)).isInstanceOf(PublicationException.class)
                .hasMessageStartingWith("PUBLICATION_MISMATCH: ");

        context.putString(PREFIX + OutputPublication.SHA256, sha("bytes"));
        assertThat(OutputPublication.intent(context, PREFIX))
                .contains(new OutputPublication.Intent("OUT.txt.7.tmp", sha("bytes")));
        assertThatThrownBy(() -> new OutputPublication.Intent("../OUT.txt", sha("bytes")))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");
        assertThatThrownBy(() -> new OutputPublication.Intent("OUT.txt.7.tmp", "ABC"))
                .isInstanceOf(PublicationException.class).hasMessageStartingWith("PUBLICATION_MISMATCH: ");
    }

    @Test
    void anIntentIsCommittedOnlyInATransaction() {
        JobExecution execution = execution(7, BatchStatus.STARTED);

        assertThatThrownBy(() -> publication.commitIntent(execution, PREFIX,
                new OutputPublication.Intent("OUT.txt.7.tmp", sha("bytes")))).isInstanceOf(IllegalStateException.class);
        assertThat(execution.getExecutionContext().isEmpty()).isTrue();
        assertThat(contextUpdates).isEmpty();
    }

    @Test
    void anIntentWhoseTransactionRollsBackLeavesTheContext() {
        JobExecution execution = execution(7, BatchStatus.STARTED);
        List<TransactionSynchronization> synchronizations = inTransaction(() -> publication.commitIntent(execution,
                PREFIX, new OutputPublication.Intent("OUT.txt.7.tmp", sha("bytes"))));
        assertThat(contextUpdates).containsExactly(7L);
        assertThat(execution.getExecutionContext().getString(PREFIX + OutputPublication.WORK_PATH)).isEqualTo("OUT.txt.7.tmp");

        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(OutputPublication.intent(execution.getExecutionContext(), PREFIX)).isEmpty();
    }

    @Test
    void aCommittedIntentStaysAndASecondOneIsRefused() {
        JobExecution execution = execution(7, BatchStatus.STARTED);
        OutputPublication.Intent intent = new OutputPublication.Intent("OUT.txt.7.tmp", sha("bytes"));
        List<TransactionSynchronization> synchronizations = inTransaction(() -> publication.commitIntent(execution,
                PREFIX, intent));

        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_COMMITTED);

        assertThat(OutputPublication.intent(execution.getExecutionContext(), PREFIX)).contains(intent);
        assertThatThrownBy(() -> inTransaction(() -> publication.commitIntent(execution, PREFIX,
                new OutputPublication.Intent("OUT.txt.8.tmp", sha("other"))))).isInstanceOf(IllegalStateException.class);
        assertThat(OutputPublication.intent(execution.getExecutionContext(), PREFIX)).contains(intent);
    }

    private static List<TransactionSynchronization> inTransaction(Runnable work) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            work.run();
            return TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void theCleanUpRemovesOnlyUnnamedWorkingFilesOfTerminalExecutionsOfTheInstance() throws Exception {
        JobExecution failed = execution(4, BatchStatus.FAILED);
        JobExecution named = execution(5, BatchStatus.FAILED);
        named.getExecutionContext().putString(PREFIX + OutputPublication.WORK_PATH, "OUT.txt.5.tmp");
        named.getExecutionContext().putString(PREFIX + OutputPublication.SHA256, sha("5"));
        JobExecution running = execution(6, BatchStatus.STARTED);
        JobExecution current = execution(7, BatchStatus.STARTED);
        executions.addAll(List.of(failed, named, running, current));
        for (String name : List.of("OUT.txt.4.tmp", "OUT.txt.5.tmp", "OUT.txt.6.tmp", "OUT.txt.7.tmp", "OUT.txt.9.tmp",
                "OUT.txt")) {
            write(folder.resolve(name), name);
        }

        List<Path> removed = publication.cleanUp(current, PREFIX, folder,
                id -> OutputPublication.workFileName("OUT.txt", id));

        assertThat(removed).containsExactly(folder.resolve("OUT.txt.4.tmp"));
        assertThat(names()).containsExactly("OUT.txt", "OUT.txt.5.tmp", "OUT.txt.6.tmp", "OUT.txt.7.tmp",
                "OUT.txt.9.tmp");
    }

    @Test
    void theCleanUpOfAJobWithoutIntentRemovesEveryTerminalExecutionsFile() throws Exception {
        JobExecution completed = execution(4, BatchStatus.COMPLETED);
        JobExecution stopped = execution(5, BatchStatus.STOPPED);
        JobExecution current = execution(7, BatchStatus.STARTED);
        executions.addAll(List.of(completed, stopped, current));
        write(folder.resolve("OUT.txt.4.tmp"), "4");
        write(folder.resolve("OUT.txt.5.tmp"), "5");

        assertThat(publication.cleanUp(current, null, folder, id -> OutputPublication.workFileName("OUT.txt", id)))
                .hasSize(2);
        assertThat(names()).isEmpty();
    }

    @Test
    void theCleanUpNeverFollowsOrRemovesALink() throws Exception {
        Path elsewhere = write(OutputRoot.ensureDirectory(temp, "elsewhere").resolve("OTHER.txt"), "bytes");
        Files.createSymbolicLink(folder.resolve("OUT.txt.4.tmp"), elsewhere);
        JobExecution current = execution(7, BatchStatus.STARTED);
        executions.addAll(List.of(execution(4, BatchStatus.FAILED), current));

        assertThatThrownBy(() -> publication.cleanUp(current, null, folder,
                id -> OutputPublication.workFileName("OUT.txt", id))).isInstanceOf(OutputRootException.class)
                .extracting(e -> ((OutputRootException) e).code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);

        assertThat(Files.isSymbolicLink(folder.resolve("OUT.txt.4.tmp"))).isTrue();
        assertThat(read(elsewhere)).isEqualTo("bytes");
    }

    @Test
    void theMessageNamesTheCodeAndTheDetail() {
        PublicationException e = new PublicationException("detail");
        assertThat(e).hasMessage("PUBLICATION_MISMATCH: detail");
        assertThat(Map.of(e.code(), e.detail())).containsEntry("PUBLICATION_MISMATCH", "detail");
    }
}
