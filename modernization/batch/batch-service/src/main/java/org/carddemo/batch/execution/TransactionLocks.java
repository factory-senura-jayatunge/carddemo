package org.carddemo.batch.execution;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** sp_getapplock owned by the current transaction, so it is released at commit or rollback. */
public final class TransactionLocks {

    private TransactionLocks() {
    }

    /** Takes the lock for the current transaction; false when it was not granted within the timeout. */
    public static boolean acquire(JdbcClient db, String resource, String mode, int timeoutMillis) {
        // A non-autocommit connection of the driver runs with IMPLICIT_TRANSACTIONS ON, and EXEC
        // opens no implicit transaction, so a lock taken first needs BEGIN TRANSACTION. At count 0
        // that opens two levels, of which commit() ends only one; the inner COMMIT just lowers the
        // count to the one transaction that commit() ends.
        Integer result = db.sql("""
                        SET NOCOUNT ON;
                        IF @@TRANCOUNT = 0
                        BEGIN
                            BEGIN TRANSACTION;
                            IF @@TRANCOUNT > 1 COMMIT TRANSACTION;
                        END;
                        DECLARE @r INT;
                        EXEC @r = sp_getapplock @Resource = :resource, @LockMode = :mode,
                            @LockOwner = 'Transaction', @LockTimeout = :timeout;
                        SELECT @r;""")
                .param("resource", resource).param("mode", mode).param("timeout", timeoutMillis)
                .query(Integer.class).single();
        requireOneLevelAtCommit(db);
        // 0 granted, 1 granted after waiting; negative: timeout, cancelled, deadlock victim or error.
        return result != null && result >= 0;
    }

    /**
     * commit() ends one level. With more open, the work and the locks would wait for Spring's
     * connection reset, which only logs a failure, so a caller could report success for work that
     * is then lost. The check runs before commit, so a failure makes Spring roll back every level
     * and nothing is durable; a check after commit could instead fail a call whose work is
     * committed, when its own query fails.
     */
    private static void requireOneLevelAtCommit(JdbcClient db) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getSynchronizations().stream()
                        .anyMatch(OneLevelAtCommit.class::isInstance)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new OneLevelAtCommit(db));
    }

    private record OneLevelAtCommit(JdbcClient db) implements TransactionSynchronization {
        @Override
        public void beforeCommit(boolean readOnly) {
            Integer levels = db.sql("SELECT @@TRANCOUNT").query(Integer.class).single();
            if (levels == null || levels != 1) {
                // Not a TransactionException: Spring takes one thrown before doCommit for a failed
                // commit and, without rollbackOnCommitFailure, rolls nothing back; the connection
                // reset would then commit a level and return the connection with the other open.
                throw new IllegalStateException("a transaction holding an application lock had " + levels
                        + " levels open before commit, not 1");
            }
        }
    }
}
