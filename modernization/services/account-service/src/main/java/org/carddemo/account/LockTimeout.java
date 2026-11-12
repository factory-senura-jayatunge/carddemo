package org.carddemo.account;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.carddemo.platform.web.SafeErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How long a save waits for a row lock: one setting for the service. {@code SET LOCK_TIMEOUT}
 * belongs to the connection, not the transaction, so it is reset before the connection goes back
 * to the pool; otherwise later reads on that connection would inherit the limit. It is sent as a
 * plain statement: inside a prepared statement the driver runs it in {@code sp_executesql}, whose
 * SET options end with the call.
 */
final class LockTimeout {

    static final int MILLIS = 5_000;

    /** SQL Server error 1222: "Lock request time out period exceeded." */
    private static final int LOCK_REQUEST_TIMED_OUT = 1222;

    private static final Logger log = LoggerFactory.getLogger(LockTimeout.class);

    private LockTimeout() {
    }

    /** Runs {@code work} on the current transaction's connection with the lock timeout set. */
    static <T> T within(JdbcTemplate jdbc, Supplier<T> work) {
        try {
            jdbc.execute("SET LOCK_TIMEOUT " + MILLIS);
            return work.get();
        } finally {
            try {
                jdbc.execute("SET LOCK_TIMEOUT -1");
            } catch (DataAccessException e) {
                log.warn("lock timeout not reset: {}", SafeErrors.describe(e));
            }
        }
    }

    static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == LOCK_REQUEST_TIMED_OUT) {
                return true;
            }
        }
        return false;
    }
}
