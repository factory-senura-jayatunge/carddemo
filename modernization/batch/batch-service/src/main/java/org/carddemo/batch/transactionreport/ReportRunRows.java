package org.carddemo.batch.transactionreport;

import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * batch-service's writes on {@code batch.report_run} (data-model.md, "Report runs"). Each one is
 * compare-and-set and runs in the caller's fenced transaction; none takes a value from the data.
 * The completion also stores the published report's SHA-256.
 */
final class ReportRunRows {

    /** The stored request: one date pair for the selection and the heading (DEF-TRPT-002 cannot occur). */
    record Range(LocalDate start, LocalDate end) { }

    private final JdbcClient db;

    ReportRunRows(JdbcClient db) {
        this.db = db;
    }

    /**
     * REQUESTED, or a restart's RUNNING or FAILED row that an execution already owned, becomes
     * RUNNING for {@code executionId}. A TRIGGER_FAILED row has no execution and is never runnable.
     *
     * @return whether the row changed
     */
    boolean markRunning(long runId, long executionId) {
        int changed = db.sql("""
                UPDATE batch.report_run
                SET status = 'RUNNING', job_execution_id = :execution, started_at = SYSUTCDATETIME(),
                    finished_at = NULL, failure_code = NULL
                WHERE report_run_id = :run
                  AND (status = 'REQUESTED' OR (status IN ('RUNNING', 'FAILED') AND job_execution_id IS NOT NULL))""")
                .param("run", runId).param("execution", executionId).update();
        return changed == 1;
    }

    /**
     * Whether the row is COMPLETED by an earlier execution of {@code instanceId}: one that committed
     * COMPLETED and then ended before the job repository recorded its end.
     */
    boolean completedByEarlier(long runId, long instanceId, long executionId) {
        return db.sql("""
                SELECT COUNT(*) FROM batch.report_run AS r
                JOIN batch.BATCH_JOB_EXECUTION AS e ON e.JOB_EXECUTION_ID = r.job_execution_id
                WHERE r.report_run_id = :run AND r.status = 'COMPLETED'
                  AND e.JOB_INSTANCE_ID = :instance AND e.JOB_EXECUTION_ID < :execution""")
                .param("run", runId).param("instance", instanceId).param("execution", executionId)
                .query(Integer.class).single() == 1;
    }

    /**
     * The row's dates, while it is RUNNING for {@code executionId}.
     *
     * @throws CatalogueFailure {@code REPORT_RUN_NOT_RUNNING} otherwise
     */
    Range running(long runId, long executionId) {
        Optional<Range> range = db.sql("""
                SELECT start_date, end_date FROM batch.report_run
                WHERE report_run_id = :run AND job_execution_id = :execution AND status = 'RUNNING'""")
                .param("run", runId).param("execution", executionId)
                .query((rs, n) -> new Range(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)))
                .optional();
        return range.orElseThrow(() -> new CatalogueFailure("REPORT_RUN_NOT_RUNNING", "report run " + runId));
    }

    /**
     * The SHA-256 a COMPLETED row was completed with; empty for a row completed before the column
     * existed, which nothing backfills.
     */
    Optional<byte[]> completedSha256(long runId) {
        return db.sql("""
                SELECT output_sha256 FROM batch.report_run
                WHERE report_run_id = :run AND status = 'COMPLETED'""")
                .param("run", runId)
                .query((rs, n) -> Optional.ofNullable(rs.getBytes(1))).single();
    }

    /** The 32 bytes of an intent's lower-case hex SHA-256, as {@code output_sha256} stores them. */
    static byte[] sha256Bytes(String sha256) {
        byte[] bytes = HexFormat.of().parseHex(sha256);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("a SHA-256 has 32 bytes");
        }
        return bytes;
    }

    /**
     * RUNNING becomes COMPLETED with {@code sha256}, the intent's hash that the publication verified
     * the target against, in the transaction that records the publication. Status and hash change
     * in one statement, so no reader sees a COMPLETED row without its hash.
     *
     * @throws CatalogueFailure {@code REPORT_RUN_NOT_RUNNING} when no row changed
     */
    void markCompleted(long runId, long executionId, String sha256) {
        byte[] hash = sha256Bytes(sha256);
        int changed = db.sql("""
                UPDATE batch.report_run
                SET status = 'COMPLETED', finished_at = SYSUTCDATETIME(), output_sha256 = :sha256
                WHERE report_run_id = :run AND job_execution_id = :execution AND status = 'RUNNING'""")
                .param("run", runId).param("execution", executionId).param("sha256", hash).update();
        if (changed != 1) {
            throw new CatalogueFailure("REPORT_RUN_NOT_RUNNING", "report run " + runId);
        }
    }

    /**
     * The row of a failed or stopped execution, if it is still RUNNING for it, becomes FAILED with
     * {@code failureCode}. A COMPLETED row is never changed.
     *
     * @return the rows changed, 0 or 1
     */
    int markFailed(long executionId, String failureCode) {
        return db.sql("""
                UPDATE batch.report_run
                SET status = 'FAILED', failure_code = :code, finished_at = SYSUTCDATETIME()
                WHERE job_execution_id = :execution AND status = 'RUNNING'""")
                .param("execution", executionId).param("code", failureCode).update();
    }
}
