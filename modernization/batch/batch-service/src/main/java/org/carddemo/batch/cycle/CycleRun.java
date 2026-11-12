package org.carddemo.batch.cycle;

import java.time.LocalDate;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/** The identifying parameters every cycle job runs with (contract section 2.1). */
record CycleRun(long cycleId, LocalDate businessDate, int attempt) {

    static CycleRun of(StepExecution step) {
        JobParameters p = step.getJobExecution().getJobParameters();
        Long cycleId = p.getLong("cycleId");
        LocalDate businessDate = p.getLocalDate("businessDate");
        Long attempt = p.getLong("attempt");
        if (cycleId == null || businessDate == null || attempt == null) {
            throw new IllegalStateException("a cycle job needs cycleId, businessDate and attempt");
        }
        return new CycleRun(cycleId, businessDate, attempt.intValue());
    }
}
