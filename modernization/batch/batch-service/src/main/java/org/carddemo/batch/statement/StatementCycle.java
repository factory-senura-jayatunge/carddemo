package org.carddemo.batch.statement;

import java.time.LocalDate;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * The identifying parameters of a statementJob instance (contract section 2.1): one run, and one
 * final folder {@code statements/<cycleId>-<attempt>}, per instance.
 */
record StatementCycle(long cycleId, LocalDate businessDate, int attempt) {

    static StatementCycle of(StepExecution step) {
        JobParameters p = step.getJobExecution().getJobParameters();
        Long cycleId = p.getLong("cycleId");
        LocalDate businessDate = p.getLocalDate("businessDate");
        Long attempt = p.getLong("attempt");
        if (cycleId == null || businessDate == null || attempt == null) {
            throw new IllegalStateException("statementJob needs cycleId, businessDate and attempt");
        }
        return new StatementCycle(cycleId, businessDate, attempt.intValue());
    }

    /** The final folder's name, also the run row's {@code folder}. */
    String folder() {
        return cycleId + "-" + attempt;
    }
}
