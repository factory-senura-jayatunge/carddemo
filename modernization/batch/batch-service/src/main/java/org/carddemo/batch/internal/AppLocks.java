package org.carddemo.batch.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.carddemo.batch.execution.TransactionLocks;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application locks of the batch window. {@code carddemo.batch_window}: online writers hold it
 * shared for each write transaction (ledger-core's JdbcBatchWindowGuard), a window open takes it
 * exclusively, so the open waits for the writes in flight. {@code carddemo.batch_launch}: a launch
 * holds it shared from its window check until its execution is committed, a window close takes it
 * exclusively, so no execution can start in a window that is being closed.
 */
final class AppLocks {

    static final String WINDOW = "carddemo.batch_window";
    static final String LAUNCH = "carddemo.batch_launch";

    private AppLocks() {
    }

    /** Takes the lock for the current transaction; false when it was not granted in time. */
    static boolean inTransaction(JdbcClient db, String resource, String mode, Duration timeout) {
        return TransactionLocks.acquire(db, resource, mode, (int) timeout.toMillis());
    }

    /**
     * Runs work while this session holds the lock shared, on a connection of its own, so the work's
     * own transactions commit while the lock is held. Empty when the lock was not granted in time.
     *
     * <p>The connection is bound to the thread for the work, so the work's statements and
     * transactions run on it: a launch then needs one pooled connection, not one for the lock and
     * more for its creates. Holding one while waiting for another would let as many launches as
     * the pool has connections take them all and wait for each other until the pool times out.
     */
    static <T> java.util.Optional<T> sharedForSession(DataSource dataSource, String resource, Duration timeout,
            Supplier<T> work) {
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("a session lock needs a thread without a bound connection");
        }
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            throw new CannotGetJdbcConnectionException("application lock " + resource, e);
        }
        try (c) {
            c.setAutoCommit(true);
            if (!sessionLock(c, resource, (int) timeout.toMillis())) {
                return java.util.Optional.empty();
            }
            boolean released = false;
            TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(c));
            try {
                T result = work.get();
                TransactionSynchronizationManager.unbindResource(dataSource);
                release(c, resource);
                released = true;
                return java.util.Optional.ofNullable(result);
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
                if (!released) {
                    try {
                        // The work may have failed inside a transaction it could not end.
                        if (!c.getAutoCommit()) {
                            c.rollback();
                            c.setAutoCommit(true);
                        }
                        release(c, resource);
                    } catch (SQLException e) {
                        // A pooled connection must not keep a session lock: drop it instead.
                        c.abort(Runnable::run);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("application lock " + resource, e);
        }
    }
    private static boolean sessionLock(Connection c, String resource, int timeoutMillis) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SET NOCOUNT ON;
                DECLARE @r INT;
                EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Shared', @LockOwner = 'Session', @LockTimeout = ?;
                SELECT @r;""")) {
            s.setString(1, resource);
            s.setInt(2, timeoutMillis);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) >= 0;
            }
        }
    }

    private static void release(Connection c, String resource) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "EXEC sp_releaseapplock @Resource = ?, @LockOwner = 'Session'")) {
            s.setString(1, resource);
            s.execute();
        }
    }
}
