package org.carddemo.batch.execution;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * What batch-service may say about a failure that it logs or hands on after a failed rollback:
 * class names and, for SQL errors, the error number and SQLState, never a message. The driver
 * quotes statement values in its messages (SQL Server error 2628 prints the truncated value).
 * The same rule as the services' SafeErrors (web-support), which batch-service does not depend on
 * because web-support brings Spring Security with it.
 */
public final class SafeFailures {

    /** Bounds a pathological cause chain; real chains are two or three deep. */
    private static final int MAX_DEPTH = 16;

    private SafeFailures() {
    }

    /** {@code UncategorizedSQLException < SQLServerException SQL error 2628 (state 22001)}. */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.size() < MAX_DEPTH && seen.add(t); t = t.getCause()) {
            sb.append(sb.isEmpty() ? "" : " < ").append(one(t));
        }
        return sb.toString();
    }

    /**
     * A copy of the cause chain with the same stack frames and no message: each copy's message is
     * the description of the one throwable it replaces, a DataAccessException, TransactionException
     * or Error stays one, and an SQLException keeps its error number and SQLState.
     */
    public static Throwable sanitized(Throwable e) {
        return copy(e, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Throwable copy(Throwable t, Set<Throwable> seen) {
        seen.add(t);
        Throwable cause = t.getCause();
        Throwable copiedCause = cause != null && seen.size() < MAX_DEPTH && !seen.contains(cause) ? copy(cause, seen) : null;
        String description = one(t);
        Throwable copy;
        if (t instanceof DataAccessException) {
            copy = new RedactedDataAccess(description, copiedCause);
        } else if (t instanceof TransactionException) {
            copy = new RedactedTransaction(description, copiedCause);
        } else if (t instanceof Error) {
            copy = new RedactedError(description, copiedCause);
        } else if (t instanceof SQLException sql) {
            copy = new RedactedSql(description, sql.getSQLState(), sql.getErrorCode(), copiedCause);
        } else {
            copy = new Redacted(description, copiedCause);
        }
        copy.setStackTrace(t.getStackTrace());
        for (Throwable suppressed : t.getSuppressed()) {
            if (seen.size() < MAX_DEPTH && !seen.contains(suppressed)) {
                copy.addSuppressed(copy(suppressed, seen));
            }
        }
        return copy;
    }

    private static String one(Throwable t) {
        if (t instanceof Copy) {
            return t.getMessage();
        }
        String name = t.getClass().getSimpleName();
        if (t instanceof SQLException sql) {
            return name + " SQL error " + sql.getErrorCode() + " (state " + sql.getSQLState() + ")";
        }
        return name;
    }

    /** A stand-in whose message is only the description of the throwable it replaces. */
    private interface Copy {
    }

    static final class Redacted extends RuntimeException implements Copy {
        Redacted(String description, Throwable cause) {
            super(description, cause);
        }
    }

    static final class RedactedDataAccess extends DataAccessException implements Copy {
        RedactedDataAccess(String description, Throwable cause) {
            super(description, cause);
        }
    }

    static final class RedactedTransaction extends TransactionException implements Copy {
        RedactedTransaction(String description, Throwable cause) {
            super(description, cause);
        }
    }

    static final class RedactedError extends Error implements Copy {
        RedactedError(String description, Throwable cause) {
            super(description, cause);
        }
    }

    static final class RedactedSql extends SQLException implements Copy {
        RedactedSql(String description, String sqlState, int errorCode, Throwable cause) {
            super(description, sqlState, errorCode, cause);
        }
    }
}
