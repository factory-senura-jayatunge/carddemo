package org.carddemo.batch.execution;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Recovery of an execution whose owner stopped renewing (contract section 4.4): revoke the
 * lease, then fail the execution, its running steps and its report, export or statement run, in
 * one transaction. Only a launch
 * calls it; a poll never does.
 */
public class ExecutionRecovery {

    /** SQL Server's lock request timeout and deadlock victim errors. */
    private static final int LOCK_TIMEOUT = 1222;
    private static final int DEADLOCK_VICTIM = 1205;

    /**
     * Prefix of every recovery statement. JDBC sends each statement through sp_executesql, and SQL
     * Server restores a SET option when that batch returns, so a separate SET LOCK_TIMEOUT
     * statement would not apply to the next one.
     */
    private static final String LOCK_WAIT = "SET LOCK_TIMEOUT 5000;\n";

    public sealed interface Outcome {
        /** The lease was revoked and the execution failed with {@code exitMessage}. */
        record Recovered(String exitMessage) implements Outcome { }

        /** Not recoverable now (the owner renewed, someone recovered first, or it ended); answer from its state. */
        record NotRecoverable() implements Outcome { }

        /** The owner holds the lease row between fence and commit: the execution is running. */
        record Locked() implements Outcome { }
    }

    private record Revoked(LocalDateTime renewedAt, String host, int pid) { }

    private final JdbcClient db;
    private final ExecutionLeases leases;
    private final SafeTransactions transaction;

    public ExecutionRecovery(JdbcClient db, ExecutionLeases leases, SafeTransactions transaction) {
        this.db = db;
        this.leases = leases;
        this.transaction = transaction;
    }

    public Outcome recover(long executionId) {
        try {
            return transaction.execute(status -> {
                Optional<Revoked> revoked = revoke(executionId);
                if (revoked.isEmpty()) {
                    status.setRollbackOnly();
                    return new Outcome.NotRecoverable();
                }
                Revoked r = revoked.get();
                String message = "OWNER_LOST: lease not renewed since " + r.renewedAt() + ", owner " + r.host()
                        + "/" + r.pid();
                fail(executionId, message);
                return new Outcome.Recovered(message);
            });
        } catch (DuplicateKeyException e) {
            return new Outcome.NotRecoverable();
        } catch (DataAccessException e) {
            if (sqlErrorCode(e) == LOCK_TIMEOUT || sqlErrorCode(e) == DEADLOCK_VICTIM) {
                return new Outcome.Locked();
            }
            throw e;
        }
    }

    private Optional<Revoked> revoke(long executionId) {
        // OUTPUT goes INTO a table variable: batch.execution_lease has a trigger, and SQL Server
        // refuses a plain OUTPUT clause on a table with enabled triggers. A lock timeout only ends
        // the statement, so CATCH re-raises it before the SELECT can answer "nothing revoked".
        Optional<Revoked> revoked = db.sql(LOCK_WAIT + """
                SET NOCOUNT ON;
                DECLARE @r TABLE (renewed_at DATETIME2(3), owner_host VARCHAR(255), owner_pid INT);
                BEGIN TRY
                    UPDATE l SET revoked_at = SYSUTCDATETIME(), revoked_reason = 'OWNER_LOST'
                    OUTPUT inserted.renewed_at, inserted.owner_host, inserted.owner_pid INTO @r
                    FROM batch.execution_lease AS l
                    JOIN batch.BATCH_JOB_EXECUTION AS e ON e.JOB_EXECUTION_ID = l.job_execution_id
                    WHERE l.job_execution_id = :id AND e.STATUS IN %s AND %s;
                END TRY
                BEGIN CATCH
                    THROW;
                END CATCH;
                SET NOCOUNT OFF;
                SELECT renewed_at, owner_host, owner_pid FROM @r;"""
                        .formatted(ExecutionLeases.RUNNING_STATUSES, ExecutionLeases.RECOVERABLE))
                .param("id", executionId)
                .query((rs, n) -> new Revoked(rs.getObject(1, LocalDateTime.class), rs.getString(2), rs.getInt(3)))
                .optional();
        if (revoked.isPresent()) {
            return revoked;
        }
        // An execution without a lease row: its LAST_UPDATED stands in for the renewal (batch-service
        // runs in UTC, so Spring Batch writes it on the same scale as SYSUTCDATETIME()), with the fixed
        // limit. The revoked row this inserts serializes recoverers on the same primary key.
        LeaseOwner me = leases.owner();
        return db.sql(LOCK_WAIT + """
                SET NOCOUNT ON;
                DECLARE @r TABLE (renewed_at DATETIME2(3), owner_host VARCHAR(255), owner_pid INT);
                BEGIN TRY
                    INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid,
                        acquired_at, renewed_at, recoverable_after_ms, revoked_at, revoked_reason)
                    OUTPUT inserted.renewed_at, inserted.owner_host, inserted.owner_pid INTO @r
                    SELECT e.JOB_EXECUTION_ID, :owner, :host, :pid, COALESCE(e.START_TIME, e.CREATE_TIME),
                           e.LAST_UPDATED, :recoverableMs, SYSUTCDATETIME(), 'OWNER_LOST'
                    FROM batch.BATCH_JOB_EXECUTION AS e WITH (UPDLOCK, HOLDLOCK)
                    WHERE e.JOB_EXECUTION_ID = :id AND e.STATUS IN %s
                      AND e.LAST_UPDATED < DATEADD(MILLISECOND, -:recoverableMs, SYSUTCDATETIME())
                      AND NOT EXISTS (SELECT 1 FROM batch.execution_lease AS x WITH (UPDLOCK, HOLDLOCK)
                                      WHERE x.job_execution_id = e.JOB_EXECUTION_ID);
                END TRY
                BEGIN CATCH
                    THROW;
                END CATCH;
                SET NOCOUNT OFF;
                SELECT renewed_at, owner_host, owner_pid FROM @r;"""
                        .formatted(ExecutionLeases.RUNNING_STATUSES))
                .param("id", executionId).param("owner", me.id().toString()).param("host", me.host())
                .param("pid", me.pid()).param("recoverableMs", ExecutionLeases.LEASELESS_RECOVERABLE_MS)
                .query((rs, n) -> new Revoked(rs.getObject(1, LocalDateTime.class), rs.getString(2), rs.getInt(3)))
                .optional();
    }

    private void fail(long executionId, String message) {
        for (String table : new String[] {"batch.BATCH_STEP_EXECUTION", "batch.BATCH_JOB_EXECUTION"}) {
            db.sql(LOCK_WAIT + """
                    UPDATE %s SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', EXIT_MESSAGE = :message,
                        END_TIME = CAST(SYSUTCDATETIME() AS DATETIME), LAST_UPDATED = CAST(SYSUTCDATETIME() AS DATETIME),
                        VERSION = VERSION + 1
                    WHERE JOB_EXECUTION_ID = :id AND STATUS IN %s"""
                            .formatted(table, ExecutionLeases.RUNNING_STATUSES))
                    .param("id", executionId).param("message", message).update();
        }
        // The run rows of the execution (data-model.md, "Report runs"; contract 4.4): never left
        // RUNNING for a FAILED execution. The restart sets RUNNING again.
        for (String table : new String[] {"batch.report_run", "batch.export_run"}) {
            db.sql(LOCK_WAIT + """
                    UPDATE %s SET status = 'FAILED', failure_code = 'OWNER_LOST', finished_at = SYSUTCDATETIME()
                    WHERE job_execution_id = :id AND status = 'RUNNING'""".formatted(table))
                    .param("id", executionId).update();
        }
        // The statement run it owns, found by job_execution_id only (batch.md, "Runs, restart and
        // rerun"). A PUBLISHING run keeps its committed intent; the restart claims and finishes it.
        db.sql(LOCK_WAIT + """
                UPDATE batch.statement_run SET status = 'FAILED', failure_code = 'OWNER_LOST',
                    changed_at = SYSUTCDATETIME()
                WHERE job_execution_id = :id AND status = 'RUNNING'""")
                .param("id", executionId).update();
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
