package org.carddemo.transaction;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The child JVM of ReportDownloadLimitsTest. Four modes, each bounded in time:
 * <ul>
 * <li>{@code serve <varDir> <runId> <sha256 hex>}: one download through the controller into a
 * hashing response, then one {@code RESULT} line with what the response and the snapshot channel
 * saw; exit 0, or 1 on any exception.</li>
 * <li>{@code pause <varDir>}: opens one snapshot with {@code DELETE_ON_CLOSE} left out, prints
 * {@code OPENED <name>} while the name still exists, and waits to be killed; exit 3 after ten
 * minutes so that an orphan ends by itself.</li>
 * <li>{@code leak <varDir> <runId> <sha256 hex> <size> <rounds>}: one warm-up round and then
 * {@code rounds} rounds of {@link ReportDownloadFixture#leakRound}, every snapshot channel kept
 * reachable; one {@code RESULT} line with the descriptor and collection counts taken right before
 * and after the measured rounds. Meant for a JVM without a collector (Epsilon), where no Cleaner
 * can close a forgotten source channel either.</li>
 * <li>{@code stall <varDir> <runId> <sha256 hex>}: one download through the controller whose
 * client takes the first body bytes, prints {@code BODY}, and then stops reading; it waits to be
 * killed, exit 3 after ten minutes.</li>
 * </ul>
 */
public final class ReportDownloadChild {

    private ReportDownloadChild() {
    }

    public static void main(String[] args) {
        try {
            switch (args.length > 0 ? args[0] : "") {
                case "serve" -> serve(Path.of(args[1]), Long.parseLong(args[2]), HexFormat.of().parseHex(args[3]));
                case "pause" -> pause(Path.of(args[1]));
                case "stall" -> stall(Path.of(args[1]), Long.parseLong(args[2]), HexFormat.of().parseHex(args[3]));
                case "leak" -> leak(Path.of(args[1]), Long.parseLong(args[2]), HexFormat.of().parseHex(args[3]),
                        Long.parseLong(args[4]), Integer.parseInt(args[5]));
                default -> {
                    System.err.println("usage: serve VAR RUN SHA256 | pause VAR | stall VAR RUN SHA256"
                            + " | leak VAR RUN SHA256 SIZE ROUNDS");
                    System.exit(2);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void serve(Path var, long runId, byte[] committed) throws IOException {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, runId, committed);
        AtomicInteger opens = new AtomicInteger();
        AtomicLong snapshotRead = new AtomicLong();
        ReportStaging.Hooks counting = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                opens.incrementAndGet();
                return new ReportServiceTest.Seam(channel) {
                    @Override
                    public int read(ByteBuffer dst) throws IOException {
                        int n = super.read(dst);
                        if (n > 0) {
                            snapshotRead.addAndGet(n);
                        }
                        return n;
                    }
                };
            }
        };
        ReportDownloadFixture.HashingResponse response = new ReportDownloadFixture.HashingResponse();
        fixture.controller(counting).downloadTransactionReport(Long.toString(runId), ReportDownloadFixture.user(),
                response);
        long entries;
        try (Stream<Path> list = Files.list(fixture.stagingFolder())) {
            entries = list.count();
        }
        System.out.println("RESULT status=" + response.getStatus()
                + " contentLength=" + response.getContentLengthLong()
                + " bodyBytes=" + response.bodyBytes()
                + " sha256=" + HexFormat.of().formatHex(response.bodySha256())
                + " opens=" + opens.get()
                + " snapshotRead=" + snapshotRead.get()
                + " maxMemory=" + Runtime.getRuntime().maxMemory()
                + " stagingEntries=" + entries);
        System.out.flush();
    }

    private static void leak(Path var, long runId, byte[] committed, long size, int rounds) throws IOException {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, runId, committed);
        ReportDownloadFixture.Written written = new ReportDownloadFixture.Written(size, committed);
        byte[] wrong = ReportDownloadFixture.sha256().digest(new byte[] {'x'});
        ReportDownloadFixture.Retaining plainHooks = new ReportDownloadFixture.Retaining(0);
        ReportDownloadFixture.Retaining failingHooks = new ReportDownloadFixture.Retaining(ReportDownloadFixture.LEAK_LIMIT);
        ReportController plain = fixture.controller(plainHooks);
        ReportController failing = fixture.controller(failingHooks);

        ReportDownloadFixture.leakRound(fixture, plain, failing, written, wrong, plainHooks, failingHooks);
        long collectionsBefore = ReportDownloadFixture.collections();
        long descriptorsBefore = ReportDownloadFixture.openDescriptors();
        for (int i = 0; i < rounds; i++) {
            ReportDownloadFixture.leakRound(fixture, plain, failing, written, wrong, plainHooks, failingHooks);
        }
        long descriptorsAfter = ReportDownloadFixture.openDescriptors();
        long collectionsAfter = ReportDownloadFixture.collections();
        System.out.println("RESULT rounds=" + rounds
                + " snapshots=" + (plainHooks.snapshots() + failingHooks.snapshots())
                + " stillOpen=" + (plainHooks.stillOpen() + failingHooks.stillOpen())
                + " descriptorsBefore=" + descriptorsBefore
                + " descriptorsAfter=" + descriptorsAfter
                + " collectionsBefore=" + collectionsBefore
                + " collectionsAfter=" + collectionsAfter
                + " collectors=" + ManagementFactory.getGarbageCollectorMXBeans().size());
        System.out.flush();
    }

    private static void stall(Path var, long runId, byte[] committed) {
        ReportDownloadFixture fixture = new ReportDownloadFixture(var, runId, committed);
        ServletOutputStream stalled = new ServletOutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                System.out.println("BODY");
                System.out.flush();
                waitToBeKilled();
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return stalled;
            }
        };
        fixture.controller(ReportStaging.Hooks.NONE).downloadTransactionReport(Long.toString(runId),
                ReportDownloadFixture.user(), response);
    }

    private static void waitToBeKilled() {
        try {
            Thread.sleep(10 * 60 * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(3);
    }

    private static void pause(Path var) throws IOException {
        ReportStaging.Hooks paused = new ReportStaging.Hooks() {
            @Override
            public Set<OpenOption> snapshotOptions(Set<OpenOption> options) {
                Set<OpenOption> kept = new HashSet<>(options);
                kept.remove(StandardOpenOption.DELETE_ON_CLOSE);
                return kept;
            }

            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                System.out.println("OPENED " + name.getFileName());
                System.out.flush();
                waitToBeKilled();
                return channel;
            }
        };
        ReportStaging.resolve(var, name -> null, paused).open().close();
    }
}
