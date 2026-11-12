package org.carddemo.batch.execution;

/**
 * A failure of the archive step's session tag (batch-execution-contract.md section 4.5). The
 * message, {@code CODE: detail}, holds no value: the detail names at most an SQL error number, and
 * the cause is {@link SafeFailures#sanitized}, so no driver message travels with it.
 */
public final class ExecutionTagException extends RuntimeException {

    /** No transaction of the data source is bound to the thread, or the step has no execution ID. */
    public static final String UNBOUND = "CONNECTION_TAG_UNBOUND";
    /** The value could not be read or the tag could not be set. */
    public static final String FAILED = "CONNECTION_TAG_FAILED";
    /** The session already carried an execution tag before the step set its own. */
    public static final String STALE = "CONNECTION_TAG_STALE";
    /** The prior value could not be set back, or did not read back. */
    public static final String NOT_RESTORED = "CONNECTION_TAG_NOT_RESTORED";

    private final String code;

    public ExecutionTagException(String code, String detail, Throwable cause) {
        super(code + ": " + detail, cause == null ? null : SafeFailures.sanitized(cause), false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The message without its code. */
    public String detail() {
        return getMessage().substring(code.length() + 2);
    }
}
