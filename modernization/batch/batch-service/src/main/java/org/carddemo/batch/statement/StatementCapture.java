package org.carddemo.batch.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;

import javax.sql.DataSource;

import org.carddemo.batch.execution.TransactionLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The capture (batch.md, "Which accounts and transactions", amended 2026-11-11): the watermark
 * {@code W}, the figures of every account with at least one cross-reference row and the RUNNING run
 * row, in the step's fenced transaction, whose fence is its last statement. Both modes take the
 * same capture; a legacy run only writes no {@code batch.account_statement} rows. Locks, in the
 * writers' order: the window application lock exclusively, the open window row of the cycle, {@code core.account} and then
 * {@code core.transaction} with {@code TABLOCK, HOLDLOCK}. A shared table lock waits for every
 * uncommitted insert or change of the table, so no row at or below {@code W} can commit after the
 * capture, whoever writes it. A lock that is not granted within the lock wait, a lock timeout
 * (1222) or a deadlock (1205) fails the start with {@code STATEMENT_CAPTURE_BUSY} and commits
 * nothing; the task retry starts again.
 *
 * <p>{@code LOCK_TIMEOUT} and {@code DEADLOCK_PRIORITY} belong to the session, so on a pooled
 * connection they would reach the next borrower. Every statement carries them as a prefix, and the
 * defaults are restored on the same bound connection before it can go back to the pool, after
 * success and after failure; a connection whose restore fails is aborted (contract section 4.4).
 */
final class StatementCapture {

    static final String BUSY = "STATEMENT_CAPTURE_BUSY";
    static final String WINDOW_REQUIRED = "BATCH_WINDOW_REQUIRED";
    static final String ATTEMPT_CHANGED = "CYCLE_ATTEMPT_CHANGED";
    static final String RESTORE_FAILED = "STATEMENT_SESSION_RESTORE_FAILED";
    static final String WINDOW_LOCK = "carddemo.batch_window";
    static final String SESSION_DEFAULTS = "SET LOCK_TIMEOUT -1; SET DEADLOCK_PRIORITY NORMAL;";
    static final String LAUNCH_LOCK_TIMEOUT = "carddemo.batch.internal.launch-lock-timeout";

    private static final int LOCK_TIMEOUT = 1222;
    private static final int DEADLOCK_VICTIM = 1205;
    private static final Logger log = LoggerFactory.getLogger(StatementCapture.class);

    private final JdbcClient db;
    private final DataSource dataSource;
    private final int lockWaitMillis;

    /** @param lockWait batch-service's lock wait, {@code carddemo.batch.internal.launch-lock-timeout} */
    StatementCapture(JdbcClient db, DataSource dataSource, Duration lockWait) {
        this.db = db;
        this.dataSource = dataSource;
        this.lockWaitMillis = millis(lockWait);
    }

    /**
     * The wait as {@code SET LOCK_TIMEOUT} and {@code sp_getapplock} take it. 0 would not wait and
     * a negative value would wait forever, so neither reaches them; a bad value fails the start.
     */
    static int millis(Duration lockWait) {
        if (lockWait == null || lockWait.isNegative() || lockWait.toMillis() < 1
                || lockWait.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(LAUNCH_LOCK_TIMEOUT + " must be at least 1 ms and at most "
                    + Integer.MAX_VALUE + " ms for the statement capture");
        }
        return (int) lockWait.toMillis();
    }

    /** @return the new run's ID, owned by {@code execution} */
    long capture(StatementCycle cycle, StatementRuns.Mode mode, long execution) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the capture runs only in the step's fenced transaction");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        RuntimeException failure = null;
        try {
            return take(cycle, mode, execution);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                restore(connection, failure);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private long take(StatementCycle cycle, StatementRuns.Mode mode, long execution) {
        String prefix = "SET LOCK_TIMEOUT " + lockWaitMillis + ";\nSET DEADLOCK_PRIORITY LOW;\n";
        try {
            if (!TransactionLocks.acquire(db, WINDOW_LOCK, "Exclusive", lockWaitMillis)) {
                throw busy("the batch window lock was not granted within " + lockWaitMillis + " ms");
            }
            int open = db.sql(prefix + """
                    SELECT COUNT(*) FROM ops.batch_window WITH (HOLDLOCK)
                    WHERE closed_at IS NULL AND cycle_id = :cycle""")
                    .param("cycle", cycle.cycleId()).query(Integer.class).single();
            if (open == 0) {
                throw new StatementFailure(WINDOW_REQUIRED, "cycle " + cycle.cycleId() + " has no open batch window");
            }
            // Serialized with cycle-rerun, which raises the attempt in its restore transaction.
            Optional<Integer> attempt = db.sql(prefix + "SELECT attempt FROM batch.cycle WITH (HOLDLOCK) WHERE cycle_id = :cycle")
                    .param("cycle", cycle.cycleId()).query(Integer.class).optional();
            if (attempt.isEmpty() || attempt.get() != cycle.attempt()) {
                throw new StatementFailure(ATTEMPT_CHANGED, "cycle " + cycle.cycleId() + " is not at attempt "
                        + cycle.attempt() + "; nothing was captured");
            }
            db.sql(prefix + "SELECT COUNT_BIG(*) FROM core.account WITH (TABLOCK, HOLDLOCK)").query(Long.class).single();
            long watermark = db.sql(prefix + "SELECT COALESCE(MAX(tran_pk), 0) FROM core.[transaction] WITH (TABLOCK, HOLDLOCK)")
                    .query(Long.class).single();
            // Only a fixed statement opens at a previous run (CK_statement_run_previous); a legacy run
            // can still be the previous run of the next fixed one.
            Long previous = mode == StatementRuns.Mode.FIXED ? previousRun(prefix, cycle).orElse(null) : null;
            long runId = db.sql(prefix + """
                    INSERT INTO batch.statement_run (cycle_id, attempt, mode, status, watermark_tran_pk, previous_run_id,
                        folder, job_execution_id)
                    OUTPUT inserted.statement_run_id
                    VALUES (:cycle, :attempt, :mode, 'RUNNING', :watermark, :previous, :folder, :execution)""")
                    .param("cycle", cycle.cycleId()).param("attempt", cycle.attempt()).param("mode", mode.name())
                    .param("watermark", watermark).param("previous", previous, Types.BIGINT)
                    .param("folder", cycle.folder()).param("execution", execution).query(Long.class).single();
            db.sql(prefix + """
                    INSERT INTO batch.statement_capture (statement_run_id, acct_id, curr_bal, credit_limit)
                    SELECT :run, a.acct_id, a.curr_bal, a.credit_limit FROM core.account AS a
                    WHERE EXISTS (SELECT 1 FROM core.card_xref AS x WHERE x.acct_id = a.acct_id)""")
                    .param("run", runId).update();
            return runId;
        } catch (DataAccessException e) {
            int code = sqlErrorCode(e);
            if (code == LOCK_TIMEOUT || code == DEADLOCK_VICTIM) {
                throw busy("a lock was not granted within " + lockWaitMillis + " ms or the capture was a deadlock"
                        + " victim (SQL error " + code + ")");
            }
            throw e;
        }
    }

    /**
     * The latest COMPLETED run of an earlier cycle, in either mode (batch.md, "Period set", amended
     * 2026-11-11): a legacy run took the same capture, so its {@code W} bounds what it covered.
     */
    private Optional<Long> previousRun(String prefix, StatementCycle cycle) {
        return db.sql(prefix + """
                SELECT TOP (1) r.statement_run_id FROM batch.statement_run AS r
                JOIN batch.cycle AS rc ON rc.cycle_id = r.cycle_id
                JOIN batch.cycle AS c ON c.cycle_id = :cycle
                WHERE r.status = 'COMPLETED' AND rc.business_date < c.business_date
                ORDER BY rc.business_date DESC, r.statement_run_id DESC""")
                .param("cycle", cycle.cycleId()).query(Long.class).optional();
    }

    /**
     * Runs in the callback's own {@code finally}, before the commit or rollback, so on the bound
     * connection: a {@code finally} after the transaction would run once the connection is back in
     * the pool.
     */
    private static void restore(Connection connection, RuntimeException failure) {
        try (Statement s = connection.createStatement()) {
            s.execute(SESSION_DEFAULTS);
        } catch (SQLException e) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abort) {
                e.addSuppressed(abort);
            }
            StatementFailure restoreFailed = new StatementFailure(RESTORE_FAILED, "the capture's session settings"
                    + " could not be reset (SQL error " + e.getErrorCode() + "); its connection was aborted");
            // A StatementFailure keeps no suppressed exceptions, so the log is where this one shows.
            log.error(restoreFailed.getMessage());
            if (failure == null) {
                throw restoreFailed;
            }
        }
    }

    private static StatementFailure busy(String detail) {
        return new StatementFailure(BUSY, detail + "; nothing was captured");
    }

    private static int sqlErrorCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        return 0;
    }
}
