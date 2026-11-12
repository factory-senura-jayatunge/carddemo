package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.carddemo.transaction.ReportDownloadFixture.RUN_ID;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.transaction.ReportDownloadFixture.HashingResponse;
import org.carddemo.transaction.ReportDownloadFixture.Written;
import org.carddemo.transaction.api.ReportApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;

/**
 * The resource limits of the report download (transaction-service.md 16, item 8), through the
 * controller's download method with the real service, output root and staging folder and an
 * in-memory run row: a file larger than the heap of a {@code -Xmx64m} child JVM, the same snapshot
 * channel hashed and served on the calling thread, a JVM killed with an open snapshot and the next
 * start's clean-up, a file swapped while downloads run, and the descriptor count over repeated
 * downloads and refusals in a child JVM without a collector. Every expected size and hash is
 * computed while the test generates the bytes, never read back from the file or the service.
 * Failure messages are constant text and numbers; a child's output goes to standard output only,
 * cut to its last lines.
 */
class ReportDownloadLimitsTest {

    private static final long CHILD_HEAP = 64L * 1024 * 1024;
    /** 2,020,000 lines of 133 bytes: 268,660,000 bytes, a little over 256 MiB and four heaps. */
    private static final long LARGE_LINES = 2_020_000;
    private static final int LEAK_ROUNDS = 50;
    private static final int TAIL_LINES = 40;
    private static final int TAIL_WIDTH = 200;
    private static final List<String> SMALL_HEAP = List.of("-Xmx64m");
    /** No collector, so no Cleaner can close a channel the service forgot. */
    private static final List<String> NO_COLLECTOR =
            List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC", "-Xmx1g");

    @TempDir
    Path temp;
    Path var;

    private final List<Process> children = new ArrayList<>();

    @BeforeEach
    void varDir() throws IOException {
        var = OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_VARIABLE, temp.resolve("var").toString())::get);
        Files.createDirectories(var);
    }

    /** No child outlives its test, whatever the outcome. */
    @AfterEach
    void killChildren() throws InterruptedException {
        for (Process child : children) {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /** 8: a body four times the child's heap is served whole, with the hash and length the job committed. */
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void aFileLargerThanTheHeapIsServedWhole() throws Exception {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, null);
        Written written = ReportDownloadFixture.writeLines(fixture.reportFile(), LARGE_LINES);
        long size = LARGE_LINES * ReportDownloadFixture.LINE_LENGTH;
        assertThat(written.size()).as("generated size").isEqualTo(size);

        Map<String, String> result = resultOf(SMALL_HEAP, 150, "serve", var.toString(), Long.toString(RUN_ID),
                HexFormat.of().formatHex(written.sha256()));

        long maxMemory = Long.parseLong(result.get("maxMemory"));
        assertThat(maxMemory).as("child max heap").isLessThanOrEqualTo(CHILD_HEAP);
        assertThat(size).as("report size against four heaps").isGreaterThan(4 * maxMemory);
        assertThat(result).containsEntry("status", "200")
                .containsEntry("contentLength", Long.toString(size))
                .containsEntry("bodyBytes", Long.toString(size))
                .containsEntry("sha256", HexFormat.of().formatHex(written.sha256()))
                .containsEntry("opens", "1")
                .containsEntry("snapshotRead", Long.toString(2 * size))
                .containsEntry("stagingEntries", "0");
    }

    /**
     * 8: the body is written by the calling thread from the one snapshot that was hashed: one open,
     * every snapshot byte read twice (hash, then body), and a method that returns nothing to run later.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void theHashedChannelIsServedOnTheCallingThread() throws Exception {
        Method api = ReportApi.class.getMethod("downloadTransactionReport", String.class, Authentication.class,
                HttpServletResponse.class);
        Method controller = ReportController.class.getMethod("downloadTransactionReport", String.class,
                Authentication.class, HttpServletResponse.class);
        assertThat(api.getReturnType()).as("ReportApi download return type").isEqualTo(void.class);
        assertThat(controller.getReturnType()).as("controller download return type").isEqualTo(void.class);
        assertThat(controller.isAnnotationPresent(Async.class)).as("@Async on the download").isFalse();
        assertThat(ReportController.class.isAnnotationPresent(Async.class)).as("@Async on the controller").isFalse();

        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, null);
        Written written = ReportDownloadFixture.writeLines(fixture.reportFile(), 40_000);
        fixture.committed = written.sha256();
        AtomicInteger opens = new AtomicInteger();
        Set<Thread> readingThreads = ConcurrentHashMap.newKeySet();
        AtomicInteger snapshotRead = new AtomicInteger();
        ReportStaging.Hooks counting = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                opens.incrementAndGet();
                return new ReportServiceTest.Seam(channel) {
                    @Override
                    public int read(ByteBuffer dst) throws IOException {
                        readingThreads.add(Thread.currentThread());
                        int n = super.read(dst);
                        if (n > 0) {
                            snapshotRead.addAndGet(n);
                        }
                        return n;
                    }
                };
            }
        };
        HashingResponse response = new HashingResponse();
        fixture.controller(counting).downloadTransactionReport(Long.toString(RUN_ID), ReportDownloadFixture.user(),
                response);

        assertThat(response.getStatus()).as("status").isEqualTo(200);
        assertThat(response.getContentLengthLong()).as("Content-Length").isEqualTo(written.size());
        assertThat(response.bodyBytes()).as("body bytes").isEqualTo(written.size());
        assertThat(response.bodySha256()).as("body sha256").isEqualTo(written.sha256());
        assertThat(opens).as("snapshot opens").hasValue(1);
        assertThat((long) snapshotRead.get()).as("snapshot bytes read").isEqualTo(2 * written.size());
        assertThat(readingThreads).as("threads that read the snapshot").containsExactly(Thread.currentThread());
        assertThat(response.writers).as("threads that wrote the body").containsExactly(Thread.currentThread());
        assertThat(entries(fixture.stagingFolder())).as("staging entries").isZero();
    }

    /**
     * 4 and 8: a JVM killed between the snapshot's open and the removal of its name leaves one
     * {@code download-<uuid>.tmp}; the next start's clean-up deletes it and keeps what is not its own.
     * With the production options the name exists only inside the open call itself, so the child
     * leaves DELETE_ON_CLOSE out and stops before the explicit delete to stand for that window.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aKilledJvmsSnapshotIsRemovedByTheNextStart() throws Exception {
        Process child = child(SMALL_HEAP, "pause", var.toString());
        ChildOutput output = new ChildOutput(child);
        try {
            String opened = output.await("OPENED ", 45);
            assertThat(opened).as("child printed OPENED").isNotNull();
            Path folder = var.resolve("private").resolve("transaction-service");
            Path leftover = folder.resolve(opened.substring("OPENED ".length()));
            assertThat(Files.isRegularFile(leftover, LinkOption.NOFOLLOW_LINKS)).as("snapshot name before the kill")
                    .isTrue();

            child.destroyForcibly();
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("child ended after SIGKILL").isTrue();
            assertThat(child.exitValue()).as("exit value after SIGKILL").isEqualTo(128 + 9);
            assertThat(Files.isRegularFile(leftover, LinkOption.NOFOLLOW_LINKS)).as("snapshot name after the kill")
                    .isTrue();
            Path notOurs = Files.write(folder.resolve("notes.txt"), new byte[] {'n'});
            Files.setPosixFilePermissions(notOurs, PosixFilePermissions.fromString("rw-------"));

            ReportStaging.CleanUp cleanUp = ReportStaging.resolve(var, name -> null).cleanUp();

            assertThat(cleanUp).as("clean-up counts").isEqualTo(new ReportStaging.CleanUp(null, 1, 1, 0, false));
            assertThat(Files.exists(leftover, LinkOption.NOFOLLOW_LINKS)).as("snapshot name after the clean-up")
                    .isFalse();
            assertThat(Files.isRegularFile(notOurs, LinkOption.NOFOLLOW_LINKS)).as("other file after the clean-up")
                    .isTrue();
        } finally {
            output.print("pause");
        }
    }

    /**
     * 3.5 and 8: a JVM killed while its client has stopped reading the body leaves no name behind,
     * because the name went before the first byte; the next start finds nothing to delete.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aJvmKilledInTheBodyLeavesNoName() throws Exception {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, null);
        Written written = ReportDownloadFixture.writeLines(fixture.reportFile(), 40_000);
        Process child = child(SMALL_HEAP, "stall", var.toString(), Long.toString(RUN_ID),
                HexFormat.of().formatHex(written.sha256()));
        ChildOutput output = new ChildOutput(child);
        try {
            assertThat(output.await("BODY", 45)).as("child printed BODY").isNotNull();
            assertThat(entries(fixture.stagingFolder())).as("staging entries in the body").isZero();

            child.destroyForcibly();
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("child ended after SIGKILL").isTrue();
            assertThat(child.exitValue()).as("exit value after SIGKILL").isEqualTo(128 + 9);

            assertThat(entries(fixture.stagingFolder())).as("staging entries after the kill").isZero();
            assertThat(ReportStaging.resolve(var, name -> null).cleanUp()).as("clean-up counts")
                    .isEqualTo(new ReportStaging.CleanUp(null, 0, 0, 0, false));
        } finally {
            output.print("stall");
        }
    }

    /** 3.6 and 8: a file renamed over the source after its open does not reach the body. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aRenameAfterTheOpenDoesNotReachTheBody() throws Exception {
        byte[] first = content(0, 20_000);
        byte[] second = content(1, 20_000);
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, sha256(first));
        Path file = fixture.reportFile();
        place(file, first, "first.txt");
        AtomicBoolean swapped = new AtomicBoolean();
        ReportStaging.Hooks swapInTheCopy = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                return new ReportServiceTest.Seam(channel) {
                    @Override
                    public int write(ByteBuffer src) throws IOException {
                        if (swapped.compareAndSet(false, true)) {
                            place(file, second, "second.txt");
                        }
                        return super.write(src);
                    }
                };
            }
        };
        assertThat(ReportDownloadFixture.once(fixture.controller(swapInTheCopy), first.length, sha256(first)))
                .as("the first file is served whole").isTrue();
        assertThat(swapped).as("the swap ran in the copy").isTrue();
    }

    /**
     * 3.6 and 8: while another thread keeps renaming the committed file and another one of the same
     * length over the source, every download is either the committed bytes whole, under their own
     * length, or the REPORT_FILE_MISSING refusal with no header and no body; nothing is left in the
     * folder. The spread of outcomes and swaps is printed.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aFileSwappedDuringDownloadsIsServedWholeOrRefused() throws Exception {
        byte[] committed = content(0, 3_000);
        byte[] other = content(1, 3_000);
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, sha256(committed));
        Path file = fixture.reportFile();
        place(file, committed, "start.txt");
        ReportController controller = fixture.controller(ReportStaging.Hooks.NONE);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> swapFailure = new AtomicReference<>();
        AtomicInteger swaps = new AtomicInteger();
        Thread swapper = new Thread(() -> {
            try {
                for (int i = 0; !stop.get(); i++) {
                    place(file, i % 2 == 0 ? other : committed, "swap-" + i + ".txt");
                    swaps.incrementAndGet();
                }
            } catch (Throwable t) {
                swapFailure.set(t);
            }
        }, "report-swapper");
        int served = 0;
        int refused = 0;
        int swapsBefore;
        int swapsAfter;
        swapper.start();
        try {
            swapsBefore = swaps.get();
            for (int i = 0; i < 200; i++) {
                if (once(controller, committed)) {
                    served++;
                } else {
                    refused++;
                }
            }
            swapsAfter = swaps.get();
        } finally {
            stop.set(true);
            swapper.join(30_000);
        }
        String spread = "served " + served + ", refused " + refused + ", swaps " + (swapsAfter - swapsBefore);
        System.out.println("report download race: " + spread);
        assertThat(swapper.isAlive()).as("swapper stopped").isFalse();
        assertThat(swapFailure.get() == null).as("swapper ran without an error").isTrue();
        assertThat(swapsAfter - swapsBefore).as("swaps during the downloads (%s)", spread).isPositive();
        assertThat(served + refused).as("downloads (%s)", spread).isEqualTo(200);

        place(file, committed, "end-committed.txt");
        assertThat(once(controller, committed)).as("the committed file is served").isTrue();
        place(file, other, "end-other.txt");
        assertThat(once(controller, committed)).as("another file is refused").isFalse();
        assertThat(entries(fixture.stagingFolder())).as("staging entries").isZero();
    }

    /**
     * 8: in a child JVM without a collector, 50 rounds of a whole download, a client abort in the
     * body, a snapshot write that fails as a full disk and a hash mismatch close every snapshot
     * channel after each round and leave the open descriptors where they were after a warm-up round.
     * Without a collector no Cleaner can close a forgotten source channel, so the count is
     * conclusive only with no collection in the window: any collection fails the test as
     * inconclusive. The smallest real leak, one path forgetting one channel, is 50 descriptors; the
     * +5 margin is for descriptors the JVM itself may open.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void repeatedDownloadsAndRefusalsLeakNoDescriptor() throws Exception {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, RUN_ID, null);
        Written written = ReportDownloadFixture.writeLines(fixture.reportFile(), 30_000);

        Map<String, String> result = resultOf(NO_COLLECTOR, 90, "leak", var.toString(), Long.toString(RUN_ID),
                HexFormat.of().formatHex(written.sha256()), Long.toString(written.size()),
                Integer.toString(LEAK_ROUNDS));

        long collectionsBefore = Long.parseLong(result.get("collectionsBefore"));
        long collectionsAfter = Long.parseLong(result.get("collectionsAfter"));
        long before = Long.parseLong(result.get("descriptorsBefore"));
        long after = Long.parseLong(result.get("descriptorsAfter"));
        System.out.println("report download descriptors: before " + before + ", after " + after
                + ", collections " + collectionsBefore + " to " + collectionsAfter);
        assertThat(collectionsBefore).as("collection counts are defined").isNotNegative();
        if (collectionsAfter != collectionsBefore) {
            throw new AssertionError("INCONCLUSIVE: " + (collectionsAfter - collectionsBefore)
                    + " garbage collections in the measured window; the descriptor count proves nothing");
        }
        assertThat(result).containsEntry("rounds", Integer.toString(LEAK_ROUNDS))
                .containsEntry("snapshots", Integer.toString(4 * (LEAK_ROUNDS + 1)))
                .containsEntry("stillOpen", "0");
        assertThat(after).as("open descriptors after %d rounds, baseline %d", LEAK_ROUNDS, before)
                .isLessThanOrEqualTo(before + 5);
    }

    private static boolean once(ReportController controller, byte[] committed) {
        return ReportDownloadFixture.once(controller, committed.length, sha256(committed));
    }

    /** Lines {@code first} to {@code first + count - 1}, as the job writes them. */
    private static byte[] content(long first, int count) {
        byte[] content = new byte[count * ReportDownloadFixture.LINE_LENGTH];
        for (int i = 0; i < count; i++) {
            System.arraycopy(ReportDownloadFixture.line(first + i), 0, content, i * ReportDownloadFixture.LINE_LENGTH,
                    ReportDownloadFixture.LINE_LENGTH);
        }
        return content;
    }

    /** As a publication does it: a new 0600 file beside the target, then one atomic rename over it. */
    private static void place(Path file, byte[] content, String tempName) throws IOException {
        Path next = file.resolveSibling(tempName);
        Files.write(next, content);
        Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"));
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] sha256(byte[] content) {
        return ReportDownloadFixture.sha256().digest(content);
    }

    private static long entries(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.count();
        }
    }

    /**
     * A child JVM on the test class path, its temporary folder the test JVM's, and no perf-data
     * file, which a killed JVM would leave in the system's temporary folder.
     */
    private Process child(List<String> jvmOptions, String... args) throws IOException {
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isBlank()) {
            classPath = System.getProperty("java.class.path");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.addAll(List.of("-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData",
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", classPath, ReportDownloadChild.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true).start();
        children.add(process);
        return process;
    }

    /** Runs a finite child to its end within {@code seconds} and returns its RESULT fields. */
    private Map<String, String> resultOf(List<String> jvmOptions, long seconds, String... args) throws Exception {
        Process child = child(jvmOptions, args);
        CompletableFuture<String> drained = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        boolean ended = child.waitFor(seconds, TimeUnit.SECONDS);
        if (!ended) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        String output = drained.get(10, TimeUnit.SECONDS);
        printTail(args[0], output.lines().toList());
        assertThat(ended).as("child %s ended within %d s", args[0], seconds).isTrue();
        assertThat(child.exitValue()).as("child %s exit value", args[0]).isZero();
        String line = output.lines().filter(l -> l.startsWith("RESULT ")).reduce((a, b) -> b).orElse(null);
        assertThat(line).as("child %s printed a RESULT line", args[0]).isNotNull();
        Map<String, String> fields = new HashMap<>();
        for (String field : line.substring("RESULT ".length()).split(" ")) {
            int eq = field.indexOf('=');
            fields.put(field.substring(0, eq), field.substring(eq + 1));
        }
        return fields;
    }

    /** The last lines of a child's output, each cut short, to standard output only. */
    private static void printTail(String mode, List<String> lines) {
        StringBuilder text = new StringBuilder("child ").append(mode).append(", last lines of its output:\n");
        for (String line : lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size())) {
            text.append(line.length() > TAIL_WIDTH ? line.substring(0, TAIL_WIDTH) + "..." : line).append('\n');
        }
        System.out.print(text);
    }

    /** A running child's output, line by line, for the lines a test waits for. */
    private static final class ChildOutput {
        private final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        private final List<String> all = Collections.synchronizedList(new ArrayList<>());

        ChildOutput(Process child) {
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null; ) {
                        all.add(line);
                        pending.add(line);
                    }
                } catch (IOException e) {
                    // The child was killed: its output ends here.
                }
            }, "report-child-output");
            reader.setDaemon(true);
            reader.start();
        }

        /** The first line that is {@code prefix} or starts with it, or null after {@code seconds}. */
        String await(String prefix, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            for (long left; (left = deadline - System.nanoTime()) > 0; ) {
                String line = pending.poll(left, TimeUnit.NANOSECONDS);
                if (line != null && line.startsWith(prefix)) {
                    return line;
                }
            }
            return null;
        }

        void print(String mode) {
            synchronized (all) {
                printTail(mode, List.copyOf(all));
            }
        }
    }
}
