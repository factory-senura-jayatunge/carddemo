package org.carddemo.transaction;

import java.time.LocalDate;

/** Starts the report job of a requested run (D-ARCH-09). */
public interface ReportTrigger {

    /**
     * Starts the run, or finds it started. Returns false only on a definite failure: the run was
     * refused, or every retry of an unknown outcome failed.
     */
    boolean trigger(long runId, LocalDate startDate, LocalDate endDate, String requestedBy);
}
