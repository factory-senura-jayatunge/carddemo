package org.carddemo.authorization.processing;

import java.sql.SQLException;
import org.springframework.core.NestedExceptionUtils;

/**
 * A SQL failure in the authorization transaction, named by its step. The message carries the step
 * and the SQL Server error number only, never a key or a value.
 */
public class AuthorizationStoreException extends RuntimeException {

    private static final int DEADLOCK_VICTIM = 1205;
    private static final int LOCK_REQUEST_TIMED_OUT = 1222;

    private final FailurePoint point;
    private final int sqlError;

    public AuthorizationStoreException(FailurePoint point, RuntimeException cause) {
        super(point + " failed (" + describe(cause) + ")", cause);
        this.point = point;
        this.sqlError = sqlError(cause);
    }

    public FailurePoint point() {
        return point;
    }

    /** The SQL Server error number, or 0 when the cause is not a SQL error. */
    public int sqlError() {
        return sqlError;
    }

    /** A deadlock victim (1205) or a lock timeout (1222): the transaction is rolled back and may be run again. */
    public boolean retryable() {
        return sqlError == DEADLOCK_VICTIM || sqlError == LOCK_REQUEST_TIMED_OUT;
    }

    static int sqlError(Throwable e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql ? sql.getErrorCode() : 0;
    }

    private static String describe(Throwable e) {
        int code = sqlError(e);
        return code != 0 ? "SQL Server error " + code : NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName();
    }
}
