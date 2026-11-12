package org.carddemo.batch.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseOwner;
import org.carddemo.batch.execution.RequestTransactions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one global WRITES_BLOCKED window (contract section 6) on ops.batch_window. Opening waits for
 * the online writes in flight (the exclusive window lock); closing waits for the launches in flight
 * (the exclusive launch lock), then refuses while an execution of the holder is still live.
 */
public class BatchWindows {

    public record Window(long windowId, Long cycleId, String windowRunKey, String mode, LocalDateTime openedAt,
            LocalDateTime closedAt) {

        boolean open() {
            return closedAt == null;
        }

        WindowHolder holder() {
            return new WindowHolder(cycleId, windowRunKey);
        }

        Map<String, Object> openBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("windowId", windowId);
            body.putAll(holder().members());
            body.put("mode", mode);
            body.put("openedAt", Times.utc(openedAt));
            return body;
        }
    }

    private static final String COLUMNS = "window_id, cycle_id, run_key, mode, opened_at, closed_at";

    private final JdbcClient db;
    private final RequestTransactions transactions;
    private final Cycles cycles;
    private final JobCatalog catalog;
    private final InternalApiProperties properties;
    private final String openedBy;

    public BatchWindows(JdbcClient db, RequestTransactions transactions, Cycles cycles, JobCatalog catalog,
            InternalApiProperties properties, LeaseOwner owner) {
        this.db = db;
        this.transactions = transactions;
        this.cycles = cycles;
        this.catalog = catalog;
        this.properties = properties;
        String by = "batch-service " + owner;
        this.openedBy = by.length() > 40 ? by.substring(0, 40) : by;
    }

    public Map<String, Object> open(WindowHolder holder) {
        return open(holder, false);
    }

    /** {@code rerun}: cycle-rerun opens the window of a completed cycle too. */
    Map<String, Object> open(WindowHolder holder, boolean rerun) {
        if (holder.cycleId() != null && cycles.find(holder.cycleId()).isEmpty()) {
            throw InternalApiException.invalidRequest("cycleId", "No cycle has this cycleId.");
        }
        return transactions.execute(status -> {
            if (!AppLocks.inTransaction(db, AppLocks.WINDOW, "Exclusive", properties.windowDrainTimeout())) {
                throw new InternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "WINDOW_DRAIN_TIMEOUT",
                        "Online writes did not drain in time; the window stays closed.", null);
            }
            // Checked under the window lock, so a completing close cannot slip in between. Only
            // cycle-rerun opens a completed cycle's window, inside its own operation.
            if (holder.cycleId() != null && !rerun) {
                Cycle cycle = cycles.find(holder.cycleId()).orElseThrow();
                if (cycle.completed()) {
                    throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_COMPLETED",
                            "The cycle is completed; only cycle-rerun opens its batch window.", null)
                            .withAll(cycle.body());
                }
            }
            Optional<Window> open = db.sql("SELECT TOP (1) " + COLUMNS
                            + " FROM ops.batch_window WITH (UPDLOCK, HOLDLOCK) WHERE closed_at IS NULL")
                    .query(BatchWindows::row).optional();
            if (open.isPresent()) {
                Window window = open.get();
                if (window.holder().equals(holder)) {
                    return window.openBody();
                }
                Map<String, Object> members = window.openBody();
                members.remove("mode");
                throw new InternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "WINDOW_BUSY",
                        "Another holder has the batch window open.", null)
                        .withAll(members)
                        .header("Retry-After", Long.toString(Math.max(1, properties.windowRetryAfter().toSeconds())));
            }
            return db.sql("INSERT INTO ops.batch_window (cycle_id, run_key, mode, opened_by) OUTPUT "
                            + "inserted.window_id, inserted.cycle_id, inserted.run_key, inserted.mode, inserted.opened_at, "
                            + "inserted.closed_at VALUES (:cycle, :runKey, 'WRITES_BLOCKED', :by)")
                    .param("cycle", holder.cycleId()).param("runKey", holder.windowRunKey()).param("by", openedBy)
                    .query(BatchWindows::row).single().openBody();
        });
    }

    /**
     * {@code complete} is required for a cycle holder and never inferred, because completing the
     * cycle is what refuses a re-trigger; a run-key holder has no cycle, so only null or false.
     */
    public Map<String, Object> close(WindowHolder holder, Boolean complete) {
        if (holder.cycleId() != null && complete == null) {
            throw InternalApiException.invalidRequest("complete", "complete is required to close a cycle's window.");
        }
        if (holder.cycleId() == null && Boolean.TRUE.equals(complete)) {
            throw InternalApiException.invalidRequest("complete", "A windowRunKey window has no cycle to complete.");
        }
        return transactions.execute(status -> {
            if (!AppLocks.inTransaction(db, AppLocks.LAUNCH, "Exclusive", properties.launchLockTimeout())) {
                throw new InternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "LAUNCH_IN_PROGRESS",
                        "A job launch for this holder is in progress. Try again later.", null)
                        .header("Retry-After", properties.launchLockRetryAfter());
            }
            Window window = db.sql("SELECT TOP (1) " + COLUMNS + " FROM ops.batch_window WITH (UPDLOCK, HOLDLOCK) "
                            + "WHERE " + (holder.cycleId() != null ? "cycle_id = :cycle" : "run_key = :runKey")
                            + " ORDER BY CASE WHEN closed_at IS NULL THEN 0 ELSE 1 END, window_id DESC")
                    .param("cycle", holder.cycleId()).param("runKey", holder.windowRunKey())
                    .query(BatchWindows::row).optional()
                    .orElseThrow(() -> new InternalApiException(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND",
                            "No batch window was opened for this holder.", null));
            Cycle cycle = null;
            if (window.open()) {
                List<Long> running = liveExecutions(holder);
                if (!running.isEmpty()) {
                    throw new InternalApiException(HttpStatus.CONFLICT, "EXECUTIONS_RUNNING",
                            "Executions of this holder are still running.", null).with("executionIds", running);
                }
                window = db.sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() OUTPUT "
                                + "inserted.window_id, inserted.cycle_id, inserted.run_key, inserted.mode, "
                                + "inserted.opened_at, inserted.closed_at WHERE window_id = :id")
                        .param("id", window.windowId()).query(BatchWindows::row).single();
                if (Boolean.TRUE.equals(complete)) {
                    cycle = cycles.complete(holder.cycleId());
                }
            }
            if (cycle == null && holder.cycleId() != null) {
                cycle = cycles.find(holder.cycleId()).orElseThrow();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("windowId", window.windowId());
            body.putAll(holder.members());
            body.put("closedAt", Times.utc(window.closedAt()));
            body.put("cycle", cycle == null ? null : cycle.body());
            return body;
        });
    }

    /** Whether exactly this holder has the window open; launches of window jobs ask it under the launch lock. */
    public boolean heldBy(WindowHolder holder) {
        return db.sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL AND "
                        + (holder.cycleId() != null ? "cycle_id = :cycle" : "run_key = :runKey"))
                .param("cycle", holder.cycleId()).param("runKey", holder.windowRunKey())
                .query(Integer.class).single() > 0;
    }

    /** Non-terminal executions of the holder's window jobs whose owner is live (not recoverable). */
    private List<Long> liveExecutions(WindowHolder holder) {
        return db.sql("""
                SELECT e.JOB_EXECUTION_ID
                FROM batch.BATCH_JOB_EXECUTION AS e
                JOIN batch.BATCH_JOB_INSTANCE AS i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
                JOIN batch.BATCH_JOB_EXECUTION_PARAMS AS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
                LEFT JOIN batch.execution_lease AS l ON l.job_execution_id = e.JOB_EXECUTION_ID
                WHERE e.STATUS IN %s AND i.JOB_NAME IN (:jobs)
                  AND p.PARAMETER_NAME = :name AND p.PARAMETER_VALUE = :value
                  AND NOT %s
                ORDER BY e.JOB_EXECUTION_ID""".formatted(ExecutionLeases.RUNNING_STATUSES,
                        ExecutionLeases.RECOVERABLE_EXECUTION))
                .param("jobs", catalog.windowJobs())
                .param("name", holder.cycleId() != null ? "cycleId" : "windowRunKey")
                .param("value", holder.cycleId() != null ? holder.cycleId().toString() : holder.windowRunKey())
                .query(Long.class).list();
    }

    static Window row(ResultSet rs, int n) throws SQLException {
        long cycle = rs.getLong("cycle_id");
        Long cycleId = rs.wasNull() ? null : cycle;
        return new Window(rs.getLong("window_id"), cycleId, rs.getString("run_key"),
                rs.getString("mode"), rs.getObject("opened_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class));
    }
}
