package org.carddemo.batch.execution;

/** The execution's lease was revoked or not renewed in time: this process may change nothing more for it. */
public class LeaseLostException extends RuntimeException {

    public static final String CODE = "LEASE_LOST";

    private final long executionId;

    public LeaseLostException(long executionId) {
        super(CODE + ": job execution " + executionId + " is no longer owned by this process");
        this.executionId = executionId;
    }

    public long executionId() {
        return executionId;
    }
}
