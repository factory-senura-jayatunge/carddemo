package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.ApiProblem;
import org.carddemo.platform.web.ProblemExceptionHandler;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.transaction.ReportRunRepository.DownloadRun;
import org.carddemo.transaction.ReportRunRepository.Requested;
import org.carddemo.transaction.ReportRunRepository.StoredRun;
import org.carddemo.transaction.api.ReportRequest;
import org.carddemo.transaction.api.ReportRun;
import org.carddemo.transaction.api.ReportStatus;
import org.carddemo.transaction.api.ReportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The request, status and download flow of ReportService against an in-memory run table and
 * trigger, without a database, and the real output root and staging folder in a scratch
 * CARDDEMO_VAR_DIR; ReportApiTest runs the same scenarios against SQL Server.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReportServiceTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 10, 14, 2, 11, 120_000_000);
    /** As SQL Server's CONVERT(CHAR(36), uniqueidentifier) renders it: upper case. */
    private static final String INSTANCE_ID = "6F9619FF-8B86-D011-B42D-00C04FC964FF";
    private static final OutputRoot.Instance INSTANCE = OutputRoot.Instance.of("carddemo", INSTANCE_ID);
    private static final OutputRoot.Instance OTHER =
            OutputRoot.Instance.of("carddemo", "0B3E2F7A-1C4D-4E5F-8A9B-0C1D2E3F4A5B");
    private static final FileAttribute<?> OWNER_ONLY_DIR =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final byte[] LINE = ("SECRET REPORT LINE" + " ".repeat(114) + "\n").getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temp;
    Path var;

    private final List<String> events = new ArrayList<>();
    private final List<StoredRun> rows = new ArrayList<>();
    private final Map<Long, byte[]> hashes = new HashMap<>();
    private int changed = 1;
    private boolean triggerAnswer = true;
    private ReportStaging.Hooks hooks = ReportStaging.Hooks.NONE;

    private final PlatformTransactionManager transactions = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    };

    private final ReportRunRepository repository = new ReportRunRepository(null) {
        @Override
        public Requested request(ReportType type, LocalDate start, LocalDate end, String requestedBy) {
            events.add("request " + type + " " + start + " " + end + " " + requestedBy);
            rows.add(new StoredRun(17, type, start, end, requestedBy, REQUESTED_AT, ReportStatus.REQUESTED, false,
                    null, null, null));
            return new Requested(17, REQUESTED_AT);
        }

        @Override
        public Optional<StoredRun> find(long runId, String userId, boolean admin, long notStartedAfterSeconds) {
            events.add("find " + runId + " " + userId + " " + admin + " " + notStartedAfterSeconds);
            return rows.stream().filter(r -> r.runId() == runId && (admin || r.requestedBy().equals(userId)))
                    .findFirst();
        }

        @Override
        public Optional<DownloadRun> findForDownload(long runId, String userId, boolean admin,
                                                     long notStartedAfterSeconds) {
            events.add("findForDownload " + runId + " " + userId + " " + admin + " " + notStartedAfterSeconds);
            return rows.stream().filter(r -> r.runId() == runId && (admin || r.requestedBy().equals(userId)))
                    .findFirst().map(r -> new DownloadRun(r, "carddemo", INSTANCE_ID, hashes.get(runId)));
        }

        @Override
        public int triggerFailed(long runId) {
            events.add("triggerFailed " + runId);
            return changed;
        }
    };

    private final ReportTrigger trigger = (runId, start, end, user) -> {
        events.add("trigger " + runId + " " + start + " " + end + " " + user);
        return triggerAnswer;
    };

    @BeforeEach
    void varDir() {
        var = OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_VARIABLE, temp.resolve("var").toString())::get);
    }

    private ReportService service() {
        Function<String, String> noSettings = name -> null;
        return service(ReportStaging.resolve(var, noSettings, hooks));
    }

    private ReportService service(ReportStaging staging) {
        ReportProperties properties = new ReportProperties(Duration.ofMinutes(15),
                new ReportProperties.Trigger(URI.create("http://127.0.0.1:1"), "u", "", 3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ofSeconds(30)));
        Clock clock = Clock.fixed(Instant.parse("2022-07-06T10:00:00Z"), ZoneOffset.UTC);
        return new ReportService(repository, trigger, properties, new ReportFiles(var), staging, clock,
                new SafeTransactionTemplate(transactions), new SafeTransactionTemplate(transactions));
    }

    private Path stagingFolder() {
        return var.resolve("private").resolve("transaction-service");
    }

    private static ReportRequest monthly(String confirm) {
        return new ReportRequest(ReportType.MONTHLY, null, null, null, null, null, null, confirm);
    }

    private static StoredRun run(long id, String user, ReportStatus status) {
        return new StoredRun(id, ReportType.CUSTOM, LocalDate.of(2022, 7, 1), LocalDate.of(2022, 7, 31), user,
                REQUESTED_AT, status, false, null, null, null);
    }

    static void refused(Executable call, HttpStatus status, String code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** S1: the row is committed before the trigger, and the 202 carries the success text. */
    @Test
    void aRequestCommitsTheRowThenTriggers() {
        ReportRun run = service().request(monthly("Y"), "USER0001");

        assertThat(events).containsExactly("request MONTHLY 2022-07-01 2022-07-31 USER0001", "commit",
                "trigger 17 2022-07-01 2022-07-31 USER0001");
        assertThat(run.runId()).isEqualTo(17);
        assertThat(run.status()).isEqualTo(ReportStatus.REQUESTED);
        assertThat(run.startDate()).isEqualTo("2022-07-01");
        assertThat(run.endDate()).isEqualTo("2022-07-31");
        assertThat(run.requestedBy()).isEqualTo("USER0001");
        assertThat(run.requestedAt()).isEqualTo("2026-11-10T14:02:11.120Z");
        assertThat(run.downloadable()).isFalse();
        assertThat(run.message()).isEqualTo("Monthly report submitted for printing ...");
    }

    /** S4, S5: nothing is written or triggered before the confirmation. */
    @Test
    void noConfirmationWritesNothing() {
        refused(() -> service().request(monthly(" "), "USER0001"), HttpStatus.UNPROCESSABLE_CONTENT,
                "CONFIRM_REQUIRED");
        refused(() -> service().request(monthly("N"), "USER0001"), HttpStatus.BAD_REQUEST, "CONFIRM_INVALID");
        ReportRequest noType = new ReportRequest(null, null, null, null, null, null, null, "Y");
        refused(() -> service().request(noType, "USER0001"), HttpStatus.BAD_REQUEST, "REPORT_TYPE_REQUIRED");
        assertThat(events).isEmpty();
    }

    /** S15: a definite trigger failure marks the run failed and answers 503. */
    @Test
    void aRefusedTriggerFailsTheRun() {
        triggerAnswer = false;
        assertThatThrownBy(() -> service().request(monthly("y"), "USER0001"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("REPORT_TRIGGER_FAILED");
                    assertThat(e.text()).isEqualTo("Unable to start the report. Try again later...");
                    assertThat(e.field()).isEqualTo("monthly");
                });
        assertThat(events).containsSubsequence("commit", "trigger 17 2022-07-01 2022-07-31 USER0001",
                "triggerFailed 17", "commit");
    }

    /** S16: the job started the run although the trigger failed; the 202 comes from the row. */
    @Test
    void aStartedRunIsKeptWhenTheTriggerFails() {
        triggerAnswer = false;
        changed = 0;
        ReportService service = service();
        ReportRun run = service.request(monthly("Y"), "USER0001");
        assertThat(events).contains("triggerFailed 17", "find 17 USER0001 true 900");
        assertThat(run.runId()).isEqualTo(17);
        assertThat(run.message()).isEqualTo("Monthly report submitted for printing ...");
    }

    /** S18: another user's run is not found; an ADMIN sees it. */
    @Test
    void runsAreTheRequestersOrAnAdmins() {
        rows.add(run(5, "USER0002", ReportStatus.RUNNING));
        refused(() -> service().status("5", "USER0001", false), HttpStatus.NOT_FOUND, "REPORT_RUN_NOT_FOUND");
        refused(() -> download("5", "USER0001", false), HttpStatus.NOT_FOUND, "REPORT_RUN_NOT_FOUND");
        refused(() -> service().status("6", "ADMIN001", true), HttpStatus.NOT_FOUND, "REPORT_RUN_NOT_FOUND");
        ReportRun run = service().status("5", "ADMIN001", true);
        assertThat(run.status()).isEqualTo(ReportStatus.RUNNING);
        assertThat(run.message()).isNull();
        assertThat(run.downloadable()).isFalse();
    }

    /** S19, S20, S25: only a completed run's file is sent, byte for byte, with its headers. */
    @Test
    void onlyACompletedRunIsDownloaded() throws IOException {
        rows.add(run(5, "USER0001", ReportStatus.RUNNING));
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        rows.add(run(7, "USER0001", ReportStatus.COMPLETED));
        byte[] content = ("REPORT LINE" + " ".repeat(122) + "\n").repeat(1000).getBytes(StandardCharsets.US_ASCII);
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, content);
        hashes.put(7L, sha256(content));

        refused(() -> download("5", "USER0001", false), HttpStatus.CONFLICT, "REPORT_NOT_READY");
        MockHttpServletResponse response = download("6", "USER0001", false);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/plain;charset=US-ASCII");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"TRANREPT-6.txt\"");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentLengthLong()).isEqualTo(content.length);
        assertThat(response.getContentAsByteArray()).isEqualTo(content);
        assertThat(download("6", "ADMIN001", true).getContentAsByteArray()).isEqualTo(content);
        refused(() -> download("7", "USER0001", false), HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_FILE_MISSING");
        assertThat(service().status("6", "USER0001", false).downloadable()).isTrue();
        assertThat(events).contains("findForDownload 6 USER0001 false 900");
        assertThat(stagingFolder()).isEmptyDirectory();
        assertThat(Files.getPosixFilePermissions(stagingFolder())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /** 3.2: a COMPLETED row without a hash (completed before V424) is refused; nothing is inferred. */
    @Test
    void aRunWithoutItsHashIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        Path file = writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        hashes.remove(6L);
        expectMissing(6, "NO_SHA256", output);
        assertThat(file).hasBinaryContent(LINE);
        assertThat(stagingFolder()).doesNotExist();
    }

    /** 3.6: a byte changed in place, or another 0600 file renamed into place, is refused with no body. */
    @Test
    void aChangedFileIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        Path file = writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        byte[] changed = LINE.clone();
        changed[3] = 'X';
        Files.write(file, changed);
        expectMissing(6, "PUBLICATION_MISMATCH", output);

        Path other = file.resolveSibling("other.txt");
        Files.write(other, ("OTHER" + " ".repeat(127) + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-------"));
        Files.move(other, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        expectMissing(6, "PUBLICATION_MISMATCH", output);
        assertThat(stagingFolder()).isEmptyDirectory();
        String log = output.getAll();
        assertThat(log).doesNotContain("SECRET", "OTHER", HexFormat.of().formatHex(sha256(LINE)), var.toString());
    }

    /** 3.6: a source changed while it is copied is refused, because the snapshot is what is hashed. */
    @Test
    void aSourceChangedDuringTheCopyIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        byte[] content = new String(LINE, StandardCharsets.US_ASCII).repeat(2000).getBytes(StandardCharsets.US_ASCII);
        Path file = writeReport(OutputRoot.ensure(var, INSTANCE), 6, content);
        hooks = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                return new Seam(channel) {
                    private boolean done;

                    @Override
                    public int write(ByteBuffer src) throws IOException {
                        if (!done) {
                            done = true;
                            try (FileChannel source = FileChannel.open(file, StandardOpenOption.WRITE)) {
                                source.write(ByteBuffer.wrap(new byte[] {'X'}), content.length - 1);
                            }
                        }
                        return super.write(src);
                    }
                };
            }
        };
        expectMissing(6, "PUBLICATION_MISMATCH", output);
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** 3.5: right after the open the snapshot has no name; nothing forces it to disk. */
    @Test
    void theSnapshotHasNoNameAndIsNeverForced() throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        List<Boolean> nameExists = new ArrayList<>();
        AtomicInteger forced = new AtomicInteger();
        List<Integer> entriesAfterHash = new ArrayList<>();
        hooks = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                nameExists.add(Files.exists(name, LinkOption.NOFOLLOW_LINKS));
                return new Seam(channel) {
                    @Override
                    public void force(boolean metaData) throws IOException {
                        forced.incrementAndGet();
                        super.force(metaData);
                    }

                    @Override
                    public FileChannel position(long newPosition) throws IOException {
                        try (var entries = Files.list(name.getParent())) {
                            entriesAfterHash.add((int) entries.count());
                        }
                        return super.position(newPosition);
                    }
                };
            }
        };
        assertThat(download("6", "USER0001", false).getContentAsByteArray()).isEqualTo(LINE);
        assertThat(nameExists).containsExactly(false);
        assertThat(forced).hasValue(0);
        assertThat(entriesAfterHash).containsOnly(0);
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** 3.5: with DELETE_ON_CLOSE made ineffective, the fallback delete removes the name. */
    @Test
    void theFallbackDeleteRemovesALeftName() throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        List<Boolean> nameExists = new ArrayList<>();
        hooks = new ReportStaging.Hooks() {
            @Override
            public Set<OpenOption> snapshotOptions(Set<OpenOption> options) {
                Set<OpenOption> without = new HashSet<>(options);
                without.remove(StandardOpenOption.DELETE_ON_CLOSE);
                return without;
            }

            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                nameExists.add(Files.exists(name, LinkOption.NOFOLLOW_LINKS));
                return channel;
            }
        };
        assertThat(download("6", "USER0001", false).getContentAsByteArray()).isEqualTo(LINE);
        assertThat(nameExists).containsExactly(true);
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** 3.5: a staging folder that is a symbolic link, or that has a group bit, is refused and left as it is. */
    @Test
    void aStagingFolderOpenToOthersIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        Path privateDir = Files.createDirectory(var.resolve("private"), OWNER_ONLY_DIR);
        Path target = Files.createDirectory(temp.resolve("elsewhere"), OWNER_ONLY_DIR);
        Files.createSymbolicLink(stagingFolder(), target);
        expectMissing(6, "OUTPUT_PERMISSIONS", output);
        assertThat(target).isEmptyDirectory();

        Files.delete(stagingFolder());
        Files.createDirectory(stagingFolder(), OWNER_ONLY_DIR);
        Files.setPosixFilePermissions(stagingFolder(), PosixFilePermissions.fromString("rwxr-x---"));
        expectMissing(6, "OUTPUT_PERMISSIONS", output);
        assertThat(Files.getPosixFilePermissions(stagingFolder())).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(stagingFolder()).isEmptyDirectory();
        assertThat(privateDir).isDirectory();
    }

    /** T4: a base removed after the start gives OUTPUT_ROOT_INVALID with no header and no body. */
    @Test
    void aBaseRemovedAfterTheStartIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        Path base = Files.createDirectory(temp.resolve("staging-base"), OWNER_ONLY_DIR);
        Function<String, String> settings = Map.of(ReportStaging.PROPERTY, base.resolve("s").toString())::get;
        ReportService service = service(ReportStaging.resolve(var, settings));
        Files.delete(base);
        MockHttpServletResponse response = new MockHttpServletResponse();
        int before = output.getAll().length();
        refused(() -> service.download("6", "USER0001", false, response), HttpStatus.INTERNAL_SERVER_ERROR,
                "REPORT_FILE_MISSING");
        assertThat(output.getAll().substring(before)).contains("report run 6: its file is not available (OUTPUT_ROOT_INVALID)")
                .doesNotContain(base.toString());
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /** 3.8: a failing snapshot write is SNAPSHOT_IO, before any header. */
    @Test
    void aFailingSnapshotWriteIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        hooks = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                return new Seam(channel) {
                    @Override
                    public int write(ByteBuffer src) throws IOException {
                        throw new IOException("No space left on device");
                    }
                };
            }
        };
        expectMissing(6, "SNAPSHOT_IO", output);
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** L1: a read-back that fails after the headers are set gives a problem without the report's headers. */
    @Test
    void anErrorBeforeTheCommitResetsTheResponse(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        hooks = new ReportStaging.Hooks() {
            @Override
            public FileChannel opened(Path name, FileChannel channel) {
                return new Seam(channel) {
                    private int rewinds;

                    @Override
                    public FileChannel position(long newPosition) throws IOException {
                        rewinds++;
                        return super.position(newPosition);
                    }

                    @Override
                    public int read(ByteBuffer dst) throws IOException {
                        if (rewinds >= 2) {
                            throw new IOException("read failed");
                        }
                        return super.read(dst);
                    }
                };
            }
        };
        MockHttpServletResponse response = download("6", "USER0001", false);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentAsString()).contains("\"code\":\"REPORT_FILE_MISSING\"").doesNotContain("SECRET");
        assertThat(output.getAll()).contains("report run 6: download ended (BODY_IO)").doesNotContain("unexpected error");
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** L2: a client that aborts in the middle of the body is logged as BODY_IO and not rethrown. */
    @Test
    void aClientAbortStaysInTheDownload(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        byte[] content = new String(LINE, StandardCharsets.US_ASCII).repeat(4000).getBytes(StandardCharsets.US_ASCII);
        writeReport(OutputRoot.ensure(var, INSTANCE), 6, content);
        MockHttpServletResponse response = new MockHttpServletResponse() {
            private final ServletOutputStream aborting = new ServletOutputStream() {
                private long written;

                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    written += len;
                    if (written > 100_000) {
                        setCommitted(true);
                        throw new IOException("Broken pipe");
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            };

            @Override
            public ServletOutputStream getOutputStream() {
                return aborting;
            }
        };
        service().download("6", "USER0001", false, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLengthLong()).isEqualTo(content.length);
        assertThat(output.getAll()).contains("report run 6: download ended (BODY_IO)").doesNotContain("unexpected error");
        assertThat(stagingFolder()).isEmptyDirectory();
    }

    /** No root yet, another instance's root, or a root without its marker or with another's: never read, never made. */
    @Test
    void onlyThisInstancesRootIsRead(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        hashes.put(6L, sha256(LINE));

        expectMissing(6, "no file", output);
        assertThat(OutputRoot.root(var, INSTANCE)).doesNotExist();

        writeReport(OutputRoot.ensure(var, OTHER), 6, LINE);
        expectMissing(6, "no file", output);

        Path root = Files.createDirectories(OutputRoot.root(var, INSTANCE), OWNER_ONLY_DIR);
        writeReport(root, 6, LINE);
        expectMissing(6, "OUTPUT_ROOT_INCOMPLETE", output);
        Path marker = Files.write(root.resolve(".instance"), OTHER.marker());
        Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
        expectMissing(6, "OUTPUT_ROOT_MISMATCH", output);
        assertThat(output.getAll()).doesNotContain("SECRET", var.toString());
    }

    /** A file or folder that others may read or write is refused, and the mode is left as it is. */
    @Test
    void aFileOpenToOthersIsNotSent(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        Path file = writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        expectMissing(6, "OUTPUT_PERMISSIONS", output);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxrwx---"));
        expectMissing(6, "OUTPUT_PERMISSIONS", output);
    }

    /** A link at the source is refused by the existing path check, before it is opened. */
    @Test
    void aLinkAtTheSourceIsRefused(CapturedOutput output) throws IOException {
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        Path file = writeReport(OutputRoot.ensure(var, INSTANCE), 6, LINE);
        Path real = Files.move(file, file.resolveSibling("real.txt"));
        Files.createSymbolicLink(file, real);
        expectMissing(6, "OUTPUT_PERMISSIONS", output);
    }

    /**
     * Contract 4.10, Messages outside the root: with a var dir whose name holds a sentinel, a download
     * refused by the root or by existingFile leaves the sentinel out of the problem body and every log
     * line, the problem handler's included, although the module's own message names it.
     */
    @Test
    void aRefusedDownloadNamesNoPathBelowTheVarDir(CapturedOutput output) throws IOException {
        String sentinel = "sentinel-a41f2c";
        var = OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_VARIABLE, temp.resolve("var-" + sentinel).toString())::get);
        rows.add(run(6, "USER0001", ReportStatus.COMPLETED));
        Path root = OutputRoot.ensure(var, INSTANCE);
        Path file = writeReport(root, 6, "LINE\n".getBytes(StandardCharsets.US_ASCII));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> OutputRoot.existingFile(root, "reports", "tranrept", "6", "TRANREPT.txt"))
                .as("the module's own message names the path").hasMessageContaining(sentinel);
        expectNoPath(6, "OUTPUT_PERMISSIONS", sentinel, output);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(root.resolve(".instance"), OTHER.marker());
        expectNoPath(6, "OUTPUT_ROOT_MISMATCH", sentinel, output);
        assertThat(output.getAll()).doesNotContain(sentinel);
    }

    private void expectNoPath(long runId, String reason, String sentinel, CapturedOutput output) {
        int before = output.getAll().length();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiException refusal = catchApiException(
                () -> service().download(Long.toString(runId), "USER0001", false, response));
        assertThat(refusal.code()).isEqualTo("REPORT_FILE_MISSING");
        ApiProblem body = new ProblemExceptionHandler().api(refusal).getBody();
        assertThat(body).isNotNull();
        assertThat(body.toJson()).contains("REPORT_FILE_MISSING").doesNotContain(sentinel);
        for (Throwable t = refusal; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(sentinel);
        }
        assertThat(output.getAll().substring(before))
                .contains("report run " + runId + ": its file is not available (" + reason + ")")
                .contains("500 REPORT_FILE_MISSING")
                .doesNotContain(sentinel);
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    private static ApiException catchApiException(Executable call) {
        try {
            call.execute();
        } catch (ApiException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("not an ApiException: " + t.getClass().getSimpleName());
        }
        throw new AssertionError("no refusal");
    }

    private MockHttpServletResponse download(String runId, String userId, boolean admin) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service().download(runId, userId, admin, response);
        return response;
    }

    private void expectMissing(long runId, String reason, CapturedOutput output) {
        int before = output.getAll().length();
        MockHttpServletResponse response = new MockHttpServletResponse();
        refused(() -> service().download(Long.toString(runId), "USER0001", false, response),
                HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_FILE_MISSING");
        assertThat(output.getAll().substring(before))
                .contains("report run " + runId + ": its file is not available (" + reason + ")");
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /** The job's file and the hash it commits with COMPLETED. */
    private Path writeReport(Path root, long runId, byte[] content) throws IOException {
        Path file = OutputRoot.ensureDirectory(root, "reports", "tranrept", Long.toString(runId)).resolve("TRANREPT.txt");
        Files.write(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        hashes.put(runId, sha256(content));
        return file;
    }

    @Test
    void aRunIdIsAPositiveBigint() {
        assertThat(ReportService.parseRunId("17")).isEqualTo(17);
        assertThat(ReportService.parseRunId("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(ReportService.parseRunId("007")).isEqualTo(7);
        assertThat(ReportService.parseRunId("0000000000000000007")).isEqualTo(7);
        for (String bad : new String[] {null, "", "0", "000", "-1", "+1", "1.5", "abc", " 1", "9223372036854775808",
                "12345678901234567890", "00000000000000000007"}) {
            refused(() -> ReportService.parseRunId(bad), HttpStatus.BAD_REQUEST, "INVALID_RUN_ID");
        }
    }

    @Test
    void timestampsAreUtcMilliseconds() {
        assertThat(ReportService.utc(LocalDateTime.of(2026, 11, 10, 14, 2, 11))).isEqualTo("2026-11-10T14:02:11.000Z");
        assertThat(ReportService.utc(null)).isNull();
    }

    /** A snapshot channel that delegates everything; a test overrides the call it fails or counts. */
    static class Seam extends FileChannel {

        private final FileChannel delegate;

        Seam(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
