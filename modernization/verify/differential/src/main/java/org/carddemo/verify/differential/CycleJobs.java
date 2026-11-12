package org.carddemo.verify.differential;

import java.time.LocalDate;
import java.util.List;

import org.carddemo.batch.internal.ExecutionOutputs;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;

/**
 * The job repository's view of one cycle attempt: the job instances are identified by
 * {@code cycleId}, {@code businessDate} and {@code attempt}, as batch-service launches them.
 */
final class CycleJobs {

    private final JobRepository repository;
    private final long cycleId;
    private final LocalDate businessDate;
    private final int attempt;
    private final JobParameters identity;
    private final String label;

    CycleJobs(JobRepository repository, long cycleId, LocalDate businessDate, int attempt) {
        this.repository = repository;
        this.cycleId = cycleId;
        this.businessDate = businessDate;
        this.attempt = attempt;
        this.identity = identity(cycleId, businessDate, attempt);
        this.label = "cycle " + cycleId + " attempt " + attempt;
    }

    private static JobParameters identity(long cycleId, LocalDate businessDate, int attempt) {
        return new JobParametersBuilder().addLong("cycleId", cycleId, true)
                .addLocalDate("businessDate", businessDate, true).addLong("attempt", (long) attempt, true)
                .toJobParameters();
    }

    /**
     * Every execution of the cycle's jobs, at any attempt up to the current one, came after
     * {@code dataLoadJob} execution {@code loadExecutionId}: a rerun restores from an earlier
     * attempt's snapshot, so that attempt counts too. Jobs that did not run are not required.
     */
    void requireAfterLoad(List<String> cycleJobs, long loadExecutionId) {
        for (int a = 1; a <= attempt; a++) {
            JobParameters attemptIdentity = identity(cycleId, businessDate, a);
            for (String jobName : cycleJobs) {
                JobInstance instance = repository.getJobInstance(jobName, attemptIdentity);
                if (instance == null) {
                    continue;
                }
                for (JobExecution e : repository.getJobExecutions(instance)) {
                    if (e.getId() < loadExecutionId) {
                        throw new ExportException(jobName + " execution " + e.getId() + " of cycle " + cycleId
                                + " attempt " + a + " started before " + LoadReport.JOB + " execution "
                                + loadExecutionId);
                    }
                }
            }
        }
    }

    /** The job's last execution, with its steps; it must have completed. */
    JobExecution completed(String jobName) {
        JobInstance instance = repository.getJobInstance(jobName, identity);
        if (instance == null) {
            throw new ExportException(jobName + " has not run for " + label);
        }
        JobExecution last = repository.getLastJobExecution(instance);
        if (last == null) {
            throw new ExportException(jobName + " has no execution for " + label);
        }
        JobExecution full = repository.getJobExecution(last.getId());
        String exit = full.getExitStatus().getExitCode();
        if (full.getStatus() != BatchStatus.COMPLETED
                || !("COMPLETED".equals(exit) || ExecutionOutputs.COMPLETED_WITH_REJECTS.equals(exit))) {
            throw new ExportException(jobName + " execution " + full.getId() + " for " + label + " is "
                    + full.getStatus() + " " + exit + ", not completed");
        }
        return full;
    }

    /** Whether the job has any instance for this cycle attempt. */
    boolean started(String jobName) {
        return repository.getJobInstance(jobName, identity) != null;
    }

    /** The named step of a completed execution; it must have completed. */
    static StepExecution step(JobExecution execution, String stepName) {
        StepExecution found = null;
        for (StepExecution s : execution.getStepExecutions()) {
            if (s.getStepName().equals(stepName)) {
                if (found != null) {
                    throw new ExportException("execution " + execution.getId() + " has two steps " + stepName);
                }
                found = s;
            }
        }
        if (found == null || found.getStatus() != BatchStatus.COMPLETED) {
            throw new ExportException("step " + stepName + " of execution " + execution.getId() + " did not complete");
        }
        return found;
    }

    /** A count the step kept in its execution context; a missing one fails. */
    static long count(StepExecution step, String key) {
        Object value = step.getExecutionContext().get(key);
        if (!(value instanceof Number n)) {
            throw new ExportException("step " + step.getStepName() + " has no count " + key);
        }
        return n.longValue();
    }
}
