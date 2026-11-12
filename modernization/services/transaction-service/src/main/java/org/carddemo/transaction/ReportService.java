package org.carddemo.transaction;

import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.ApiProblem;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.carddemo.transaction.ReportRequestRules.Range;
import org.carddemo.transaction.ReportRunRepository.DownloadRun;
import org.carddemo.transaction.ReportRunRepository.Requested;
import org.carddemo.transaction.ReportRunRepository.StoredRun;
import org.carddemo.transaction.api.ReportRequest;
import org.carddemo.transaction.api.ReportRun;
import org.carddemo.transaction.api.ReportStatus;
import org.carddemo.transaction.api.ReportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * CORPT00C: a report request becomes one {@code batch.report_run} row and one DAG run, which
 * batch-service's {@code transactionReportJob} executes (D-48, D-ARCH-09); the requester and any
 * ADMIN read the run and download its file. The request writes no table that a batch window
 * blocks, so it is accepted during a window.
 */
@Service
public class ReportService {

    static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");

    static final int BUFFER_SIZE = 64 * 1024;
    static final String SNAPSHOT_IO = "SNAPSHOT_IO";
    static final String BODY_IO = "BODY_IO";

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRunRepository repository;
    private final ReportTrigger trigger;
    private final ReportProperties properties;
    private final ReportFiles files;
    private final ReportStaging staging;
    private final Clock clock;
    private final SafeTransactionTemplate readTx;
    private final SafeTransactionTemplate tx;

    public ReportService(ReportRunRepository repository, ReportTrigger trigger, ReportProperties properties,
                         ReportFiles files, ReportStaging staging, Clock clock,
                         SafeTransactionTemplate transactionReadTx, SafeTransactionTemplate transactionWriteTx) {
        this.repository = repository;
        this.trigger = trigger;
        this.properties = properties;
        this.files = files;
        this.staging = staging;
        this.clock = clock;
        this.readTx = transactionReadTx;
        this.tx = transactionWriteTx;
    }

    /**
     * Steps 2 to 8. The row is committed before the trigger, so that the job finds it; a definite
     * trigger failure marks it failed unless the job has already started it.
     */
    public ReportRun request(ReportRequest request, String userId) {
        Range range = ReportRequestRules.range(request, LocalDate.now(clock));
        ReportType type = request.type();
        ReportRequestRules.confirm(request.confirm(), type, range);

        Requested requested = tx.execute(status -> repository.request(type, range.start(), range.end(), userId));
        String message = MessageCatalog.REPORT_SUBMITTED.render(type.label());
        if (trigger.trigger(requested.runId(), range.start(), range.end(), userId)) {
            return new ReportRun(requested.runId(), type, range.start().toString(), range.end().toString(), userId,
                    utc(requested.requestedAt()), ReportStatus.REQUESTED, false, null, null, null, false, message);
        }
        int changed = tx.execute(status -> repository.triggerFailed(requested.runId()));
        if (changed == 1) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.REPORT_TRIGGER_FAILED,
                    ReportRequestRules.TYPE_FIELD);
        }
        log.info("report run {}: the trigger failed, but the job had already started it", requested.runId());
        StoredRun row = readTx.execute(status -> repository.find(requested.runId(), userId, true, notStartedAfter()))
                .orElseThrow(() -> new IllegalStateException("report run " + requested.runId() + " vanished"));
        return view(row, message);
    }

    public ReportRun status(String runId, String userId, boolean admin) {
        return view(stored(runId, userId, admin), null);
    }

    /**
     * Writes the file of a completed run to {@code response}, synchronously on the request thread
     * (transaction-service.md 16). The file is under the instance root of the run's database, found
     * by the run's number only, never by user text. It is opened once, copied into a nameless private
     * snapshot, and the snapshot is hashed through the same channel and compared with the sha256 that
     * the job committed with {@code COMPLETED}; only then are the status and headers set, and the
     * same channel is served. No audit row: the report holds account IDs and amounts and no card
     * number (D-10). Every refusal before the headers is the same {@code REPORT_FILE_MISSING} answer;
     * the log names the run and a reason code, never a path, the content or a hash. Nothing is
     * repaired or backfilled.
     */
    public void download(String runId, String userId, boolean admin, HttpServletResponse response) {
        long id = parseRunId(runId);
        DownloadRun found = readTx.execute(status -> repository.findForDownload(id, userId, admin, notStartedAfter()))
                .orElseThrow(ReportService::notFound);
        StoredRun run = found.run();
        if (run.status() != ReportStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, MessageCatalog.REPORT_NOT_READY, null);
        }
        byte[] expected = found.sha256();
        if (expected == null) {
            throw missing(run.runId(), "NO_SHA256");
        }
        Path file;
        try {
            file = files.find(OutputRoot.Instance.of(found.database(), found.instanceId()), run.runId())
                    .orElseThrow(() -> missing(run.runId(), "no file"));
        } catch (OutputRootException e) {
            throw missing(run.runId(), e.code().name());
        } catch (UncheckedIOException e) {
            throw missing(run.runId(), e.getClass().getSimpleName());
        }
        SeekableByteChannel source;
        try {
            source = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw missing(run.runId(), e.getClass().getSimpleName());
        }
        boolean bodyPhase = false;
        try (source; FileChannel snapshot = staging.open()) {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            copy(source, snapshot, buffer);
            source.close();
            snapshot.position(0);
            MessageDigest digest = sha256();
            long size = 0;
            for (int n; (n = snapshot.read(buffer.clear())) != -1; ) {
                digest.update(buffer.flip());
                size += n;
            }
            if (!MessageDigest.isEqual(digest.digest(), expected)) {
                throw missing(run.runId(), "PUBLICATION_MISMATCH");
            }
            bodyPhase = true;
            serve(run.runId(), snapshot, size, buffer, response);
        } catch (OutputRootException e) {
            throw missing(run.runId(), e.code().name());
        } catch (IOException | UncheckedIOException e) {
            if (bodyPhase) {
                // Only the channels' close can get here: serve keeps its own IOExceptions.
                log.warn("report run {}: download ended ({})", run.runId(), BODY_IO);
                return;
            }
            throw missing(run.runId(), SNAPSHOT_IO);
        }
    }

    private static void copy(SeekableByteChannel source, FileChannel snapshot, ByteBuffer buffer) throws IOException {
        while (source.read(buffer.clear()) != -1) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                snapshot.write(buffer);
            }
        }
    }

    /**
     * 3.7 and 3.8: the status and headers, then exactly {@code size} bytes of the hashed snapshot.
     * Every IOException of this phase, a client abort included, stays here: it is logged as
     * {@code BODY_IO} at WARN and never reaches the exception handler. Before the commit the
     * response is reset first, so a problem body never goes out under the report's headers; after
     * it, the connection ends short of {@code Content-Length}.
     */
    private static void serve(long runId, FileChannel snapshot, long size, ByteBuffer buffer,
                              HttpServletResponse response) {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(ReportController.REPORT_TEXT.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"TRANREPT-" + runId + ".txt\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setContentLengthLong(size);
        try {
            snapshot.position(0);
            OutputStream out = response.getOutputStream();
            long left = size;
            while (left > 0) {
                int n = snapshot.read(buffer.clear().limit((int) Math.min(buffer.capacity(), left)));
                if (n == -1) {
                    throw new EOFException("snapshot shorter than its hash's count");
                }
                out.write(buffer.array(), 0, n);
                left -= n;
            }
            out.flush();
        } catch (IOException e) {
            log.warn("report run {}: download ended ({}), correlation ID {}", runId, BODY_IO,
                    MDC.get(CorrelationIdFilter.MDC_KEY));
            if (!response.isCommitted()) {
                refuseInBody(runId, response);
            }
        }
    }

    private static void refuseInBody(long runId, HttpServletResponse response) {
        try {
            response.reset();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(ApiProblem.MEDIA_TYPE.toString());
            ApiProblem problem = ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.REPORT_FILE_MISSING, null);
            OutputStream out = response.getOutputStream();
            out.write(problem.toJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | IllegalStateException e) {
            log.warn("report run {}: refusal not sent ({})", runId, BODY_IO);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }

    /** A refusal before any header: the run ID and the reason are logged, nothing else. */
    private static ApiException missing(long runId, String reason) {
        log.error("report run {}: its file is not available ({}), correlation ID {}", runId, reason,
                MDC.get(CorrelationIdFilter.MDC_KEY));
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, MessageCatalog.REPORT_FILE_MISSING, null);
    }

    private StoredRun stored(String runId, String userId, boolean admin) {
        long id = parseRunId(runId);
        return readTx.execute(status -> repository.find(id, userId, admin, notStartedAfter()))
                .orElseThrow(ReportService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, MessageCatalog.REPORT_RUN_NOT_FOUND, null);
    }

    /** A positive integer that fits the BIGINT key; anything else is {@code INVALID_RUN_ID}. */
    static long parseRunId(String runId) {
        if (runId != null && runId.matches("[0-9]{1,19}")) {
            try {
                long id = Long.parseLong(runId);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException e) {
                // Above Long.MAX_VALUE: no run has that ID.
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_RUN_ID, null);
    }

    private long notStartedAfter() {
        return properties.notStartedAfter().toSeconds();
    }

    static ReportRun view(StoredRun r, String message) {
        return new ReportRun(r.runId(), r.type(), r.startDate().toString(), r.endDate().toString(), r.requestedBy(),
                utc(r.requestedAt()), r.status(), r.stalled(), utc(r.startedAt()), utc(r.finishedAt()),
                r.failureCode(), r.status() == ReportStatus.COMPLETED, message);
    }

    static String utc(LocalDateTime timestamp) {
        return timestamp == null ? null : UTC_MILLIS.format(timestamp);
    }
}
