package org.carddemo.batch.statement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rows of {@code batch.statement_run} (batch.md, "Runs, restart and rerun", owner binding).
 * {@code job_execution_id} names the one execution that may write a row: the capture inserts it
 * with its own execution, and a restart claims it. statementJob changes a row only by
 * compare-and-set on its expected status and on that execution, in its step's fenced transaction;
 * 0 updated rows fail the step with {@code STATEMENT_RUN_NOT_OWNED}. A row's watermark, mode and
 * folder are written once, by the capture, and no statement here changes them.
 *
 * <p>The static methods are the changes other paths make to the rows, each in its caller's
 * transaction: cycle-rerun and dataLoadJob, which act on runs whose executions are all terminal.
 * Lease recovery's change is in {@code ExecutionRecovery}, by {@code job_execution_id} only.
 */
public final class StatementRuns {

    enum Mode { FIXED, LEGACY }

    enum Status { RUNNING, FAILED, PUBLISHING, COMPLETED, SUPERSEDED, INVALIDATED }

    /** A run's counts; null columns until the intent. */
    record Counts(int statements, long transactions, long unassigned, int adjusted) {

        Counts {
            if (statements < 0 || transactions < 0 || unassigned < 0 || adjusted < 0) {
                throw new IllegalArgumentException("a count is negative");
            }
        }

        /** Rows no statement lists and adjusted accounts, in both modes (D-57; batch.md, "Build rulings", Q3). */
        long rejects() {
            return unassigned + adjusted;
        }
    }

    record Run(long id, long cycleId, int attempt, Mode mode, Status status, long watermark, Long previousRunId,
            String folder, String workFolder, String manifestSha256, Counts counts, long jobExecutionId) {

        Run claimedBy(long execution, Status to) {
            return new Run(id, cycleId, attempt, mode, to, watermark, previousRunId, folder, workFolder,
                    manifestSha256, counts, execution);
        }
    }

    /** No run row where one must be, or a status the step does not handle. */
    static final String CHANGED = "STATEMENT_RUN_CHANGED";
    /** The run is not this execution's: a compare-and-set updated 0 rows, or the row names another execution. */
    static final String NOT_OWNED = "STATEMENT_RUN_NOT_OWNED";

    private static final String COLUMNS = """
            statement_run_id, cycle_id, attempt, mode, status, watermark_tran_pk, previous_run_id, folder,
            work_folder, manifest_sha256, statements, transactions, unassigned, adjusted, job_execution_id""";

    private final JdbcClient db;

    StatementRuns(JdbcClient db) {
        this.db = db;
    }

    /**
     * The run of a cycle attempt, update-locked to the end of the transaction, for the first step to
     * decide between the capture and a restart's claim. It changes nothing by this lookup.
     */
    Optional<Run> find(long cycleId, int attempt) {
        return db.sql("SELECT " + COLUMNS + " FROM batch.statement_run WITH (UPDLOCK)"
                        + " WHERE cycle_id = :cycle AND attempt = :attempt")
                .param("cycle", cycleId).param("attempt", attempt).query(StatementRuns::run).optional();
    }

    /**
     * The run of a cycle attempt for a later step of {@code execution}, update-locked when
     * {@code lock}. A RUNNING or PUBLISHING run must name this execution, and a FAILED one (its
     * owner was recovered) belongs to no running execution: both fail with
     * {@code STATEMENT_RUN_NOT_OWNED}. A COMPLETED run is returned whoever completed it, for the
     * check of its published folder only; a SUPERSEDED or INVALIDATED one fails with
     * {@code STATEMENT_RUN_INVALIDATED}.
     */
    Run owned(StatementCycle cycle, long execution, boolean lock) {
        Run run = db.sql("SELECT " + COLUMNS + " FROM batch.statement_run" + (lock ? " WITH (UPDLOCK)" : "")
                        + " WHERE cycle_id = :cycle AND attempt = :attempt")
                .param("cycle", cycle.cycleId()).param("attempt", cycle.attempt()).query(StatementRuns::run).optional()
                .orElseThrow(() -> new StatementFailure(CHANGED, "cycle " + cycle.cycleId() + " attempt "
                        + cycle.attempt() + " has no statement run"));
        switch (run.status()) {
            case COMPLETED -> {
                return run;
            }
            case SUPERSEDED, INVALIDATED -> throw new StatementFailure(CaptureTasklet.RUN_INVALIDATED, "statement run "
                    + run.id() + " is " + run.status() + "; rerun the cycle to issue its statements");
            case FAILED -> throw notOwned(run, execution);
            case RUNNING, PUBLISHING -> {
                if (run.jobExecutionId() != execution) {
                    throw notOwned(run, execution);
                }
                return run;
            }
        }
        throw new IllegalStateException(run.status().name());
    }

    /** {@code MAX(tran_pk)} now, 0 for an empty table. */
    long maxTranPk() {
        return db.sql("SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction]").query(Long.class).single();
    }

    /**
     * A restart's claim, a compare-and-set on the status and on the execution it read: a RUNNING or
     * FAILED run becomes RUNNING with {@code execution}, keeping its watermark and captured figures;
     * a PUBLISHING run keeps its status and gets {@code execution}. No other status is claimed.
     */
    Run claim(Run run, long execution) {
        Status to = switch (run.status()) {
            case RUNNING, FAILED -> Status.RUNNING;
            case PUBLISHING -> Status.PUBLISHING;
            default -> throw new IllegalArgumentException("a " + run.status() + " run is never claimed");
        };
        cas(db.sql("""
                UPDATE batch.statement_run SET status = :to, job_execution_id = :execution, failure_code = NULL,
                    changed_at = SYSUTCDATETIME()
                WHERE statement_run_id = :id AND status = :expected AND job_execution_id = :owner""")
                .param("to", to.name()).param("execution", execution).param("id", run.id())
                .param("expected", run.status().name()).param("owner", run.jobExecutionId()).update(),
                run, execution, to);
        return run.claimedBy(execution, to);
    }

    /** A fixed run's account rows, deleted before it renders again; only while this execution owns it RUNNING. */
    int deleteAccountRows(Run run, long execution) {
        return db.sql("""
                DELETE FROM batch.account_statement
                WHERE statement_run_id = :id AND EXISTS (SELECT 1 FROM batch.statement_run
                    WHERE statement_run_id = :id AND status = 'RUNNING' AND job_execution_id = :execution)""")
                .param("id", run.id()).param("execution", execution).update();
    }

    /** The working folder of every run that names one: no clean-up removes them. */
    List<String> workFolders() {
        return db.sql("SELECT work_folder FROM batch.statement_run WHERE work_folder IS NOT NULL")
                .query(String.class).list();
    }

    /** A fixed run's statement files by name, with their SHA-256. */
    Map<String, String> accountFiles(long runId) {
        Map<String, String> files = new TreeMap<>();
        db.sql("SELECT file_name, file_sha256 FROM batch.account_statement WHERE statement_run_id = :id")
                .param("id", runId).query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .forEach(e -> files.put(e.getKey(), e.getValue()));
        return files;
    }

    /** Publication step 1's commit: the intent, RUNNING to PUBLISHING. */
    void publishing(Run run, long execution, String workFolder, String manifestSha256, Counts counts) {
        cas(db.sql("""
                UPDATE batch.statement_run SET status = 'PUBLISHING', work_folder = :work, manifest_sha256 = :sha,
                    statements = :statements, transactions = :transactions, unassigned = :unassigned,
                    adjusted = :adjusted, changed_at = SYSUTCDATETIME()
                WHERE statement_run_id = :id AND status = 'RUNNING' AND job_execution_id = :execution""")
                .param("id", run.id()).param("execution", execution).param("work", workFolder)
                .param("sha", manifestSha256).param("statements", counts.statements())
                .param("transactions", counts.transactions()).param("unassigned", counts.unassigned())
                .param("adjusted", counts.adjusted()).update(),
                run, execution, Status.PUBLISHING);
    }

    /** Publication step 3's commit, after the final folder verified. */
    void completed(Run run, long execution) {
        cas(db.sql("""
                UPDATE batch.statement_run SET status = 'COMPLETED', completed_at = SYSUTCDATETIME(),
                    changed_at = SYSUTCDATETIME()
                WHERE statement_run_id = :id AND status = 'PUBLISHING' AND job_execution_id = :execution""")
                .param("id", run.id()).param("execution", execution).update(),
                run, execution, Status.COMPLETED);
    }

    private static void cas(int updated, Run run, long execution, Status to) {
        if (updated != 1) {
            throw new StatementFailure(NOT_OWNED, "statement run " + run.id() + " is no longer " + run.status()
                    + " with job execution " + run.jobExecutionId() + "; job execution " + execution
                    + " did not set it " + to);
        }
    }

    private static StatementFailure notOwned(Run run, long execution) {
        return new StatementFailure(NOT_OWNED, "statement run " + run.id() + " is " + run.status()
                + " with job execution " + run.jobExecutionId() + ", not job execution " + execution);
    }

    private static Run run(ResultSet rs, int n) throws SQLException {
        Counts counts = null;
        int statements = rs.getInt("statements");
        if (!rs.wasNull()) {
            counts = new Counts(statements, rs.getLong("transactions"), rs.getLong("unassigned"),
                    rs.getInt("adjusted"));
        }
        long previousId = rs.getLong("previous_run_id");
        Long previous = rs.wasNull() ? null : previousId;
        return new Run(rs.getLong("statement_run_id"), rs.getLong("cycle_id"), rs.getInt("attempt"),
                Mode.valueOf(rs.getString("mode")), Status.valueOf(rs.getString("status")),
                rs.getLong("watermark_tran_pk"), previous, rs.getString("folder"),
                rs.getString("work_folder"), rs.getString("manifest_sha256"), counts,
                rs.getLong("job_execution_id"));
    }

    // --- changes made by other paths, in their callers' transactions ---------------------------

    /**
     * cycle-rerun's refusal, in its restore transaction after the existing checks: the cycles after
     * {@code cycleId} (by business date) that have a COMPLETED statement run. Replacing an earlier
     * period would break a statement already issued. The later runs are read with
     * {@code UPDLOCK, HOLDLOCK}, so none becomes COMPLETED before the restore commits.
     */
    public static int laterCompleted(JdbcClient db, long cycleId) {
        return db.sql("""
                SELECT COUNT(DISTINCT r.cycle_id) FROM batch.statement_run AS r WITH (UPDLOCK, HOLDLOCK)
                JOIN batch.cycle AS later ON later.cycle_id = r.cycle_id
                JOIN batch.cycle AS c ON c.cycle_id = :c
                WHERE later.business_date > c.business_date AND r.status = 'COMPLETED'""")
                .param("c", cycleId).query(Integer.class).single();
    }

    /**
     * cycle-rerun's restore: the cycle's COMPLETED run is SUPERSEDED (its folder stays as archive),
     * and every RUNNING, FAILED or PUBLISHING run of this cycle and of every later cycle is
     * INVALIDATED, so none restarts with a watermark from before the restore.
     */
    public static void endForRerun(JdbcClient db, long cycleId) {
        db.sql("""
                UPDATE batch.statement_run SET status = 'SUPERSEDED', changed_at = SYSUTCDATETIME()
                WHERE cycle_id = :c AND status = 'COMPLETED'""").param("c", cycleId).update();
        db.sql("""
                UPDATE r SET status = 'INVALIDATED', changed_at = SYSUTCDATETIME()
                FROM batch.statement_run AS r
                JOIN batch.cycle AS rc ON rc.cycle_id = r.cycle_id
                JOIN batch.cycle AS c ON c.cycle_id = :c
                WHERE rc.business_date >= c.business_date AND r.status IN ('RUNNING', 'FAILED', 'PUBLISHING')""")
                .param("c", cycleId).update();
    }

    /**
     * dataLoadJob's load transaction: core.transaction is emptied, so every COMPLETED run is
     * SUPERSEDED and every RUNNING, FAILED or PUBLISHING run INVALIDATED. The next statement is a
     * first statement for every account.
     */
    public static void endForDataLoad(JdbcClient db) {
        db.sql("""
                UPDATE batch.statement_run
                SET status = CASE status WHEN 'COMPLETED' THEN 'SUPERSEDED' ELSE 'INVALIDATED' END,
                    changed_at = SYSUTCDATETIME()
                WHERE status IN ('COMPLETED', 'RUNNING', 'FAILED', 'PUBLISHING')""").update();
    }
}
