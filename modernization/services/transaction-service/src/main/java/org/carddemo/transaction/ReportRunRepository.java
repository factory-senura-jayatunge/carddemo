package org.carddemo.transaction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.carddemo.transaction.api.ReportStatus;
import org.carddemo.transaction.api.ReportType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code batch.report_run} as transaction-service may touch it: the request and the trigger
 * failure through procedures (no table write is granted), and a read of the granted columns.
 * Timestamps are UTC, as the table's defaults and batch-service write them.
 */
@Repository
public class ReportRunRepository {

    /** What the request procedure answers. */
    public record Requested(long runId, LocalDateTime requestedAt) {
    }

    /** A run row with its stalled flag; {@code status} is already {@code NOT_STARTED} when due. */
    public record StoredRun(long runId, ReportType type, LocalDate startDate, LocalDate endDate, String requestedBy,
                            LocalDateTime requestedAt, ReportStatus status, boolean stalled, LocalDateTime startedAt,
                            LocalDateTime finishedAt, String failureCode) {
    }

    /**
     * A run row with {@code DB_NAME()}, the instance ID as SQL Server renders it, and the sha256 the
     * job committed with {@code COMPLETED}; null for any other status and for a run completed before V424.
     */
    public record DownloadRun(StoredRun run, String database, String instanceId, byte[] sha256) {
    }

    private static final String FIND = runQuery("", "", "");
    private static final String DOWNLOAD = runQuery(
            ",\n    DB_NAME() AS database_name, CONVERT(CHAR(36), i.instance_id) AS instance_id, r.output_sha256",
            "\nCROSS JOIN ops.database_instance AS i", " AND i.id = 1");

    private final JdbcClient jdbc;

    public ReportRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new {@code REQUESTED} row; the procedure leaves the status to its default. */
    public Requested request(ReportType type, LocalDate start, LocalDate end, String requestedBy) {
        return jdbc.sql("""
                        EXEC batch.report_run_request @report_type = :type, @start_date = :start,
                            @end_date = :end, @requested_by = :requestedBy""")
                .param("type", type.name())
                .param("start", start)
                .param("end", end)
                .param("requestedBy", requestedBy)
                .query((rs, n) -> new Requested(rs.getLong("report_run_id"),
                        rs.getObject("requested_at", LocalDateTime.class)))
                .single();
    }

    /**
     * The run if it is the user's or the caller is an ADMIN; the same empty answer otherwise, so
     * a user cannot tell another user's run from a missing one. A {@code REQUESTED} row older than
     * {@code notStartedAfterSeconds} on the database clock, which set {@code requested_at}, reads
     * as {@code NOT_STARTED}; nothing is written.
     */
    public Optional<StoredRun> find(long runId, String userId, boolean admin, long notStartedAfterSeconds) {
        return jdbc.sql(FIND)
                .param("after", notStartedAfterSeconds)
                .param("id", runId)
                .param("admin", admin ? 1 : 0)
                .param("userId", userId)
                .query(ReportRunRepository::storedRun)
                .optional();
    }

    /**
     * The same read as {@link #find}, with {@code DB_NAME()}, the instance marker and the committed
     * sha256 in the same statement, so that the file is looked for under the root of the database the
     * row came from, also after a reset while the service runs, and checked against the hash of that
     * same row (batch-execution-contract.md 4.10, transaction-service.md 16).
     */
    public Optional<DownloadRun> findForDownload(long runId, String userId, boolean admin,
                                                 long notStartedAfterSeconds) {
        return jdbc.sql(DOWNLOAD)
                .param("after", notStartedAfterSeconds)
                .param("id", runId)
                .param("admin", admin ? 1 : 0)
                .param("userId", userId)
                .query((rs, n) -> new DownloadRun(storedRun(rs, n), rs.getString("database_name"),
                        rs.getString("instance_id"), rs.getBytes("output_sha256")))
                .optional();
    }

    private static String runQuery(String columns, String join, String where) {
        return """
                SELECT r.report_run_id, r.report_type, r.start_date, r.end_date, r.requested_by,
                    r.requested_at, r.started_at, r.finished_at, r.failure_code,
                    CASE WHEN r.status = 'REQUESTED'
                              AND r.requested_at < DATEADD(SECOND, -:after, SYSUTCDATETIME())
                         THEN 'NOT_STARTED' ELSE r.status END AS status,
                    COALESCE(s.stalled, CAST(0 AS BIT)) AS stalled%s
                FROM batch.report_run AS r
                LEFT JOIN batch.v_report_run_stalled AS s ON s.report_run_id = r.report_run_id%s
                WHERE r.report_run_id = :id AND (:admin = 1 OR r.requested_by = :userId)%s""".formatted(columns, join,
                where);
    }

    /**
     * Marks a still-{@code REQUESTED} run {@code FAILED}/{@code TRIGGER_FAILED}. Returns the
     * procedure's {@code changed}: 0 when the job has already started the run. The count is read
     * from the result set, because the update count of an {@code EXEC} with {@code NOCOUNT} is not.
     */
    public int triggerFailed(long runId) {
        return jdbc.sql("EXEC batch.report_run_trigger_failed @report_run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single();
    }

    private static StoredRun storedRun(ResultSet rs, int n) throws SQLException {
        return new StoredRun(rs.getLong("report_run_id"), ReportType.valueOf(rs.getString("report_type").trim()),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                TransactionFormats.trimTrailing(rs.getString("requested_by")),
                rs.getObject("requested_at", LocalDateTime.class), ReportStatus.valueOf(rs.getString("status").trim()),
                rs.getBoolean("stalled"), rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class), rs.getString("failure_code"));
    }
}
