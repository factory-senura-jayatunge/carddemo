package org.carddemo.platform.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** sp_getapplock owned by the current transaction, so it is released at commit or rollback. */
final class AppLocks {

    private AppLocks() {
    }

    /** Returns false when the lock was not granted within the timeout. */
    static boolean acquire(JdbcClient jdbc, String resource, String mode, int timeoutMillis) {
        // A non-autocommit connection of the driver runs with IMPLICIT_TRANSACTIONS ON, and EXEC
        // does not open the implicit transaction, so a lock taken first needs BEGIN TRANSACTION.
        // At count 0 that opens two levels, of which commit() ends only one; the inner COMMIT just
        // lowers the count to the one transaction that commit() ends.
        Integer result = jdbc.sql("""
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
        requireOneLevelAtCommit(jdbc);
        // 0 granted, 1 granted after waiting, negative: timeout, cancelled, deadlock victim or error.
        return result != null && result >= 0;
    }

    /**
     * commit() ends one level, so with any other count the work and the locks would outlive it and
     * be left to Spring's connection reset, which only logs a failure: a caller could answer success
     * for work that is then lost. The check runs before commit, where an exception makes Spring roll
     * back every level and fail the call; after commit nothing more is sent, so a session lost once
     * the work is durable cannot turn a committed call into a failed one.
     */
    private static void requireOneLevelAtCommit(JdbcClient jdbc) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(OneLevelAtCommit.class::isInstance)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new OneLevelAtCommit(jdbc));
    }

    private record OneLevelAtCommit(JdbcClient jdbc) implements TransactionSynchronization {
        @Override
        public void beforeCommit(boolean readOnly) {
            Integer levels = jdbc.sql("SELECT @@TRANCOUNT").query(Integer.class).single();
            if (levels == null || levels != 1) {
                throw new IllegalStateException("a transaction holding an application lock had " + levels
                        + " levels at commit instead of 1");
            }
        }
    }
}
