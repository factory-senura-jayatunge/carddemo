package org.carddemo.identity.user;

import org.carddemo.platform.web.ApiException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application locks of user administration, owned by the caller's transaction. The batch
 * window check is ledger-core's (D-BAT-05): USRSEC is closed during batch like the other files.
 */
@Component
public class UserAdminLocks {

    static final String BATCH_WINDOW_LOCK = "carddemo.batch_window";
    static final String ADMINS_LOCK = "carddemo.iam.admins";
    static final int LOCK_TIMEOUT_MILLIS = 5_000;
    static final String RETRY_AFTER_SECONDS = "300";

    private final JdbcClient jdbc;

    public UserAdminLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code 503 BATCH_WINDOW} while a window is open or opening. */
    public void requireWritesAllowed() {
        // Not granted in time means the batch holds it exclusively: a window is opening.
        boolean granted = acquire(BATCH_WINDOW_LOCK, "Shared");
        if (!granted || jdbc.sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                .query(Integer.class).single() > 0) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UserMessages.USER_ADMIN_BATCH_WINDOW, null)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
    }

    /** Serialises the changes that can remove an administrator (D-06). */
    public void lockAdmins() {
        if (!acquire(ADMINS_LOCK, "Exclusive")) {
            throw new CannotAcquireLockException(ADMINS_LOCK + " not granted in time");
        }
    }

    private boolean acquire(String resource, String mode) {
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
                .param("resource", resource).param("mode", mode).param("timeout", LOCK_TIMEOUT_MILLIS)
                .query(Integer.class).single();
        requireClosedAtCommit();
        // 0 granted, 1 granted after waiting, negative: timeout, cancelled, deadlock victim or error.
        return result != null && result >= 0;
    }

    /**
     * A second level still open at commit() would leave the work uncommitted for Spring's connection
     * reset, which only logs a failure, so the service could answer success for work that is then
     * lost. The check runs before commit, so a failure rolls back every level (the driver's
     * rollback ends them all) and nothing runs after the real commit that could report committed
     * work as failed. It must throw a plain RuntimeException: processCommit handles a
     * TransactionException from beforeCommit without rolling back (rollbackOnCommitFailure is
     * off), and the cleanup's setAutoCommit(true) would then commit a level. The caller maps
     * {@link OpenLevelAtCommit} to the operation's own failure message.
     */
    private void requireClosedAtCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getSynchronizations().stream()
                        .anyMatch(ClosedAtCommit.class::isInstance)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new ClosedAtCommit(this));
    }

    /** The open transaction levels of the caller's connection. */
    Integer levels() {
        return jdbc.sql("SELECT @@TRANCOUNT").query(Integer.class).single();
    }

    private record ClosedAtCommit(UserAdminLocks locks) implements TransactionSynchronization {
        @Override
        public void beforeCommit(boolean readOnly) {
            Integer levels = locks.levels();
            if (levels == null || levels != 1) {
                throw new OpenLevelAtCommit(levels);
            }
        }
    }

    /** A transaction holding a user administration lock had more than the one level commit() ends. */
    public static final class OpenLevelAtCommit extends IllegalStateException {
        OpenLevelAtCommit(Integer levels) {
            super("a transaction holding a user administration lock had " + levels + " levels at commit");
        }
    }
}
