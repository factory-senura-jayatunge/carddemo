package org.carddemo.transaction.api;

/**
 * A report run's status. {@code NOT_STARTED} is never stored: it is a {@code REQUESTED} row older
 * than {@code carddemo.transaction.report.not-started-after}.
 */
public enum ReportStatus {
    REQUESTED,
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    FAILED
}
