package org.carddemo.platform.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;

/** sp_getapplock owned by the current transaction, so it is released at commit or rollback. */
final class AppLocks {

    private AppLocks() {
    }

    /** Returns false when the lock was not granted within the timeout. */
    static boolean acquire(JdbcClient jdbc, String resource, String mode, int timeoutMillis) {
        // The driver runs a non-autocommit connection with IMPLICIT_TRANSACTIONS ON, and EXEC does
        // not open the implicit transaction, so a lock taken first would have no transaction to own it.
        Integer result = jdbc.sql("""
                        SET NOCOUNT ON;
                        IF @@TRANCOUNT = 0 BEGIN TRANSACTION;
                        DECLARE @r INT;
                        EXEC @r = sp_getapplock @Resource = :resource, @LockMode = :mode,
                            @LockOwner = 'Transaction', @LockTimeout = :timeout;
                        SELECT @r;""")
                .param("resource", resource).param("mode", mode).param("timeout", timeoutMillis)
                .query(Integer.class).single();
        // 0 granted, 1 granted after waiting, negative: timeout, cancelled, deadlock victim or error.
        return result != null && result >= 0;
    }
}
