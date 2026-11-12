package org.carddemo.batch.cycle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.outputroot.OutputRootException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * Puts a fixed description, instead of a stack trace, into the exit description of the failed
 * job, where the operator reads it: a {@link CycleJobException}'s message, or, for an
 * {@link OutputRootException} or a {@link PublicationException} that left a step outside
 * {@link SafeTasklet} (the fence fails a commit), its code and a fixed reason with the step's
 * name, never its message, which can name a path below CARDDEMO_VAR_DIR (contract section 4.10,
 * Messages outside the root). The step keeps Spring Batch's own description: a step listener's
 * exit status is joined to it, it cannot replace it. A completed
 * job that counted rejects exits {@code COMPLETED_WITH_REJECTS}, the legacy return code 4.
 */
final class CycleExitDescriptions implements JobExecutionListener {

    private static final int MAX_DEPTH = 16;

    @Override
    public void afterJob(JobExecution job) {
        if (job.getStatus() == BatchStatus.COMPLETED
                && job.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT) instanceof Number n
                && n.longValue() > 0) {
            job.setExitStatus(new ExitStatus(ExecutionOutputs.COMPLETED_WITH_REJECTS));
        }
        if (job.getStatus() == BatchStatus.FAILED) {
            find(job).ifPresent(m -> job.setExitStatus(new ExitStatus(ExitStatus.FAILED.getExitCode(), m)));
        }
    }

    private static Optional<String> find(JobExecution job) {
        for (Throwable failure : job.getAllFailureExceptions()) {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable c = failure; c != null && seen.size() < MAX_DEPTH && seen.add(c); c = c.getCause()) {
                if (c instanceof CycleJobException) {
                    // Built by this package from codes, IDs and record numbers only.
                    return Optional.of(c.getMessage());
                }
                if (c instanceof OutputRootException root) {
                    return Optional.of(root.code().name() + ": " + step(job, failure) + SafeTasklet.OUTPUT_ROOT_REFUSED);
                }
                if (c instanceof PublicationException publication) {
                    return Optional.of(publication.code() + ": " + step(job, failure) + SafeTasklet.PUBLICATION_REFUSED);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code step <name> failed: } for the step that recorded {@code failure}; empty for a job-level failure. */
    private static String step(JobExecution job, Throwable failure) {
        for (StepExecution step : job.getStepExecutions()) {
            for (Throwable recorded : step.getFailureExceptions()) {
                if (recorded == failure) {
                    return "step " + step.getStepName() + " failed: ";
                }
            }
        }
        return "";
    }
}
