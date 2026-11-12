package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.transaction.ReportRunRepository.DownloadRun;
import org.carddemo.transaction.ReportRunRepository.StoredRun;
import org.carddemo.transaction.api.ReportStatus;
import org.carddemo.transaction.api.ReportType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * One COMPLETED run of USER0001 in a scratch CARDDEMO_VAR_DIR, without a database: the real
 * controller, service, output root and staging folder over an in-memory run row. Shared by
 * ReportDownloadLimitsTest and the child JVM it starts (ReportDownloadChild).
 */
final class ReportDownloadFixture {

    static final String INSTANCE_ID = "6F9619FF-8B86-D011-B42D-00C04FC964FF";
    static final OutputRoot.Instance INSTANCE = OutputRoot.Instance.of("carddemo", INSTANCE_ID);
    static final String USER = "USER0001";
    /** One report line as the job writes it: 132 characters and a newline. */
    static final int LINE_LENGTH = 133;
    /** The run every test downloads. */
    static final long RUN_ID = 6;
    /** Where a client aborts in the descriptor test, and where its full disk starts. */
    static final long LEAK_LIMIT = 256 * 1024;

    final Path var;
    final long runId;
    /** The sha256 committed with COMPLETED; a test may change it to stand for another publication. */
    volatile byte[] committed;

    ReportDownloadFixture(Path var, long runId, byte[] committed) {
        this.var = var;
        this.runId = runId;
        this.committed = committed;
    }

    Path stagingFolder() {
        return var.resolve("private").resolve("transaction-service");
    }

    /** Where the job publishes the run's file, the folders created as the job would. */
    Path reportFile() {
        return OutputRoot.ensureDirectory(OutputRoot.ensure(var, INSTANCE), "reports", "tranrept", Long.toString(runId))
                .resolve(ReportFiles.FILE_NAME);
    }

    ReportController controller(ReportStaging.Hooks hooks) {
        return new ReportController(service(hooks));
    }

    ReportService service(ReportStaging.Hooks hooks) {
        PlatformTransactionManager transactions = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        ReportRunRepository repository = new ReportRunRepository(null) {
            @Override
            public Optional<DownloadRun> findForDownload(long id, String userId, boolean admin, long notStartedAfter) {
                if (id != runId || !(admin || USER.equals(userId))) {
                    return Optional.empty();
                }
                StoredRun row = new StoredRun(runId, ReportType.CUSTOM, LocalDate.of(2022, 7, 1),
                        LocalDate.of(2022, 7, 31), USER, LocalDateTime.of(2022, 7, 6, 10, 0),
                        ReportStatus.COMPLETED, false, LocalDateTime.of(2022, 7, 6, 10, 1),
                        LocalDateTime.of(2022, 7, 6, 10, 2), null);
                return Optional.of(new DownloadRun(row, "carddemo", INSTANCE_ID, committed));
            }
        };
        ReportProperties properties = new ReportProperties(Duration.ofMinutes(15),
                new ReportProperties.Trigger(URI.create("http://127.0.0.1:1"), "u", "", 3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ofSeconds(30)));
        Clock clock = Clock.fixed(Instant.parse("2022-07-06T10:00:00Z"), ZoneOffset.UTC);
        ReportTrigger trigger = (id, start, end, user) -> true;
        return new ReportService(repository, trigger, properties, new ReportFiles(var),
                ReportStaging.resolve(var, name -> null, hooks), clock,
                new SafeTransactionTemplate(transactions), new SafeTransactionTemplate(transactions));
    }

    /**
     * Hooks that keep every snapshot channel, the real one and the seam around it, reachable until
     * the test ends: the Cleaner of an unreachable FileChannelImpl would close a channel the service
     * forgot after any garbage collection, and hide that leak. {@code failAfter}, if positive, makes
     * a snapshot write past that many bytes fail as a full disk does.
     */
    static final class Retaining implements ReportStaging.Hooks {
        final List<FileChannel> channels = new CopyOnWriteArrayList<>();
        private final long failAfter;

        Retaining(long failAfter) {
            this.failAfter = failAfter;
        }

        @Override
        public FileChannel opened(Path name, FileChannel channel) {
            FileChannel seam = new ReportServiceTest.Seam(channel) {
                private long copied;

                @Override
                public int write(ByteBuffer src) throws IOException {
                    if (failAfter > 0 && copied > failAfter) {
                        throw new IOException("No space left on device");
                    }
                    int n = super.write(src);
                    copied += n;
                    return n;
                }
            };
            channels.add(channel);
            channels.add(seam);
            return seam;
        }

        int snapshots() {
            return channels.size() / 2;
        }

        long stillOpen() {
            return channels.stream().filter(FileChannel::isOpen).count();
        }
    }

    /**
     * One download into a fresh response. True when the body is {@code size} bytes with that sha256
     * under the same Content-Length; false for REPORT_FILE_MISSING with no header and no body byte.
     */
    static boolean once(ReportController controller, long size, byte[] sha256) {
        HashingResponse response = new HashingResponse();
        try {
            controller.downloadTransactionReport(Long.toString(RUN_ID), user(), response);
        } catch (ApiException e) {
            assertThat(e.status()).as("refusal status").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.code()).as("refusal code").isEqualTo("REPORT_FILE_MISSING");
            assertThat(response.getHeaderNames()).as("headers of a refusal").isEmpty();
            assertThat(response.bodyBytes()).as("body bytes of a refusal").isZero();
            return false;
        }
        assertThat(response.getStatus()).as("status").isEqualTo(200);
        assertThat(response.getContentLengthLong()).as("Content-Length").isEqualTo(size);
        assertThat(response.bodyBytes()).as("body bytes").isEqualTo(size);
        assertThat(response.bodySha256()).as("body sha256").isEqualTo(sha256);
        return true;
    }

    /**
     * One round of the descriptor test, each path once: a whole download, a client that aborts after
     * {@link #LEAK_LIMIT} bytes, a snapshot write that fails as a full disk, and a hash mismatch.
     * Afterwards every snapshot channel the hooks ever saw must be closed.
     */
    static void leakRound(ReportDownloadFixture fixture, ReportController plain, ReportController failing,
                          Written written, byte[] wrong, Retaining... hooks) {
        fixture.committed = written.sha256();
        assertThat(once(plain, written.size(), written.sha256())).as("the whole file is served").isTrue();

        HashingResponse aborted = new HashingResponse(LEAK_LIMIT);
        plain.downloadTransactionReport(Long.toString(fixture.runId), user(), aborted);
        assertThat(aborted.getStatus()).as("status of an aborted body").isEqualTo(200);
        assertThat(aborted.bodyBytes()).as("bytes the aborting client took").isLessThanOrEqualTo(LEAK_LIMIT);

        assertThat(once(failing, written.size(), written.sha256())).as("a full disk is refused").isFalse();

        fixture.committed = wrong;
        assertThat(once(plain, written.size(), written.sha256())).as("a hash mismatch is refused").isFalse();
        fixture.committed = written.sha256();
        for (Retaining retaining : hooks) {
            assertThat(retaining.stillOpen()).as("snapshot channels still open").isZero();
        }
    }

    /** The process's open descriptors: Linux lists them in /proc/self/fd, macOS in /dev/fd. */
    static long openDescriptors() throws IOException {
        Path proc = Path.of("/proc/self/fd");
        Path dir = Files.isDirectory(proc) ? proc : Path.of("/dev/fd");
        try (Stream<Path> list = Files.list(dir)) {
            return list.count();
        }
    }

    /** The collections of every collector so far, or -1 when one of them does not count. */
    static long collections() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            if (count < 0) {
                return -1;
            }
            total += count;
        }
        return total;
    }

    static Authentication user() {
        return new UsernamePasswordAuthenticationToken(USER, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    record Written(long size, byte[] sha256) {
    }

    /**
     * Writes {@code lines} numbered report lines to a new {@code 0600} file and hashes the bytes as
     * they are generated, so the expected hash never comes from reading the file back.
     */
    static Written writeLines(Path file, long lines) throws IOException {
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long size = 0;
        try (FileChannel out = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            for (long i = 0; i < lines; i++) {
                byte[] line = line(i);
                if (buffer.remaining() < line.length) {
                    drain(buffer, out);
                }
                buffer.put(line);
                digest.update(line);
                size += line.length;
            }
            drain(buffer, out);
        }
        return new Written(size, digest.digest());
    }

    static byte[] line(long i) {
        String text = String.format("%-132s%n", "TRANREPT LINE " + i);
        byte[] line = text.replace(System.lineSeparator(), "\n").getBytes(StandardCharsets.US_ASCII);
        if (line.length != LINE_LENGTH) {
            throw new IllegalStateException("line length " + line.length);
        }
        return line;
    }

    private static void drain(ByteBuffer buffer, FileChannel out) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            out.write(buffer);
        }
        buffer.clear();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A response whose body is hashed and counted as it is written, never kept, so a body larger
     * than the heap fits. {@code abortAfter} bytes, if positive, end it as a client that closes its
     * socket. It records each thread that wrote to it.
     */
    static final class HashingResponse extends MockHttpServletResponse {
        private final MessageDigest digest = sha256();
        private final AtomicLong bodyBytes = new AtomicLong();
        private final long abortAfter;
        private final ServletOutputStream body = new Body();
        final Set<Thread> writers = ConcurrentHashMap.newKeySet();

        HashingResponse() {
            this(0);
        }

        HashingResponse(long abortAfter) {
            this.abortAfter = abortAfter;
        }

        private final class Body extends ServletOutputStream {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writers.add(Thread.currentThread());
                if (abortAfter > 0 && bodyBytes.get() + len > abortAfter) {
                    setCommitted(true);
                    throw new IOException("Broken pipe");
                }
                digest.update(b, off, len);
                bodyBytes.addAndGet(len);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return body;
        }

        long bodyBytes() {
            return bodyBytes.get();
        }

        byte[] bodySha256() {
            return digest.digest();
        }
    }
}
