package org.carddemo.batch.execution;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Another launch created an execution of the instance after this launch read the instance's
 * state, so this one starts nothing. Nothing was written: the caller decides again from the
 * instance's current state.
 */
public final class ConcurrentLaunchException extends OptimisticLockingFailureException {

    private final long executionId;

    ConcurrentLaunchException(JobInstance instance, JobExecution last) {
        super("job instance " + instance.getInstanceId() + " of " + instance.getJobName() + " got execution "
                + last.getId() + " (" + last.getStatus() + ") from another launch");
        this.executionId = last.getId();
    }

    public long executionId() {
        return executionId;
    }
}
