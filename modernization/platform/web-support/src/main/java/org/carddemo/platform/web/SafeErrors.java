package org.carddemo.platform.web;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * What a log line may say about a failure (D-NFR-04, D-58). Exception messages are never logged:
 * the driver quotes statement values in them (SQL Server error 2628 prints the truncated value, a
 * conversion error the value it could not convert), and parsers quote request text. A failure is
 * described by its class names and, for SQL errors, the error number and SQLState only.
 */
public final class SafeErrors {

    /** Bounds a pathological cause chain; real chains are two or three deep. */
    private static final int MAX_DEPTH = 16;

    /**
     * A throwable whose own message holds no request or database value. A type that also takes a
     * cause must store {@link #sanitized} of it, and should disable suppression, so that the whole
     * chain stays message-free: a suppressed throwable that is not makes the type count as not
     * message-free, and {@link #messageFree} then replaces it by a copy of another type.
     */
    public interface MessageFree {
    }

    private SafeErrors() {
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
     * A copy of the cause chain with the same stack frames and no message. Each copy's message is
     * {@link #describe} of the one throwable it replaces, and it keeps that throwable's kind: a
     * {@link DataAccessException}, {@link TransactionException} or {@link Error} stays one, and an
     * {@link SQLException} keeps its error number and SQLState, so code that maps a failure by type
     * or by SQL error number still can.
     */
    public static Throwable sanitized(Throwable e) {
        return copy(e, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * {@code e} itself when its whole chain is message-free, else its {@link #sanitized} copy. A
     * transaction callback throws this, so that nothing the framework logs while it rolls back
     * holds a value (Spring logs the callback's exception, with its causes, when the rollback fails).
     */
    public static RuntimeException messageFree(RuntimeException e) {
        return isMessageFree(e) ? e : (RuntimeException) sanitized(e);
    }

    static boolean isMessageFree(Throwable e) {
        return isMessageFree(e, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean isMessageFree(Throwable t, Set<Throwable> seen) {
        if (t == null || seen.contains(t)) {
            return true;
        }
        if (seen.size() >= MAX_DEPTH || !(t instanceof MessageFree)) {
            return false;
        }
        seen.add(t);
        for (Throwable suppressed : t.getSuppressed()) {
            if (!isMessageFree(suppressed, seen)) {
                return false;
            }
        }
        return isMessageFree(t.getCause(), seen);
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
    private interface Copy extends MessageFree {
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
