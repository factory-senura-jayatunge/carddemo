package org.carddemo.batch.statement;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Statement run rows for tests of the paths that act on them without running statementJob:
 * cycle-rerun, the data load and recovery. Each row is owned by a terminal job execution of its
 * own, with a negative ID, as {@code job_execution_id} requires; nothing counts it as running.
 */
public final class StatementRunFixtures {

    private StatementRunFixtures() {
    }

    /**
     * A run of the cycle's current attempt in {@code status}, with the columns that status's CHECK
     * requires; {@code execution} must be negative and is created as a COMPLETED execution.
     *
     * @return the run's ID
     */
    public static long insert(JdbcClient admin, long cycleId, String status, long execution) {
        execution(admin, execution);
        int published = switch (status) {
            case "PUBLISHING", "COMPLETED", "SUPERSEDED" -> 1;
            case "RUNNING", "FAILED", "INVALIDATED" -> 0;
            default -> throw new IllegalArgumentException(status);
        };
        return admin.sql("""
                INSERT INTO batch.statement_run (cycle_id, attempt, mode, status, watermark_tran_pk, folder, work_folder,
                    manifest_sha256, failure_code, statements, transactions, unassigned, adjusted, completed_at,
                    job_execution_id)
                OUTPUT inserted.statement_run_id
                SELECT c.cycle_id, c.attempt, 'LEGACY', :status, 0, CONCAT(c.cycle_id, '-', c.attempt),
                    CASE WHEN :published = 1 THEN CONCAT(c.cycle_id, '-', c.attempt, '.', -:execution) END,
                    CASE WHEN :published = 1 THEN REPLICATE('0', 64) END,
                    CASE WHEN :status = 'FAILED' THEN 'OWNER_LOST' END,
                    CASE WHEN :published = 1 THEN 0 END, CASE WHEN :published = 1 THEN 0 END,
                    CASE WHEN :published = 1 THEN 0 END, CASE WHEN :published = 1 THEN 0 END,
                    CASE WHEN :status IN ('COMPLETED', 'SUPERSEDED') THEN SYSUTCDATETIME() END,
                    :execution
                FROM batch.cycle AS c WHERE c.cycle_id = :cycle""")
                .param("status", status).param("published", published).param("execution", execution)
                .param("cycle", cycleId).query(Long.class).single();
    }

    /** A COMPLETED job execution of its own instance; {@code execution} must be negative. */
    public static void execution(JdbcClient admin, long execution) {
        if (execution >= 0) {
            throw new IllegalArgumentException("fixture executions are negative");
        }
        admin.sql("""
                INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
                VALUES (:id, 0, 'statementJob', CONCAT('statementRunFixture', :id))""").param("id", execution).update();
        admin.sql("""
                INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME,
                    START_TIME, END_TIME, LAST_UPDATED, STATUS, EXIT_CODE)
                VALUES (:id, 1, :id, GETUTCDATE(), GETUTCDATE(), GETUTCDATE(), GETUTCDATE(), 'COMPLETED', 'COMPLETED')""")
                .param("id", execution).update();
    }

    public static String status(JdbcClient admin, long runId) {
        return admin.sql("SELECT status FROM batch.statement_run WHERE statement_run_id = :r").param("r", runId)
                .query(String.class).single();
    }

    /** Removes the runs of the fixture executions, their account and capture rows, then the executions. */
    public static void drop(JdbcClient admin, long... executions) {
        for (long execution : executions) {
            for (String table : new String[] {"batch.account_statement", "batch.statement_capture"}) {
                admin.sql("""
                        DELETE x FROM %s AS x JOIN batch.statement_run AS r ON r.statement_run_id = x.statement_run_id
                        WHERE r.job_execution_id = :id""".formatted(table)).param("id", execution).update();
            }
            admin.sql("DELETE FROM batch.statement_run WHERE job_execution_id = :id").param("id", execution).update();
            admin.sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id").param("id", execution)
                    .update();
            admin.sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id").param("id", execution)
                    .update();
        }
    }
}
