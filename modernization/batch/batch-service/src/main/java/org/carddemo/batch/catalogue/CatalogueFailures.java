package org.carddemo.batch.catalogue;

import org.carddemo.batch.execution.SafeFailures;
import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.platform.outputroot.OutputRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;

/** Sanitizes tasklet failures and job exit descriptions; commit-time step traces are retained. */
public final class CatalogueFailures implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(CatalogueFailures.class);

    public static Tasklet safe(Tasklet delegate) {
        return (StepContribution contribution, ChunkContext context) -> {
            try {
                return delegate.execute(contribution, context);
            } catch (CatalogueFailure | LeaseLostException e) {
                throw e;
            } catch (OutputRootException e) {
                throw new CatalogueFailure(e.code().name(), "output root refused");
            } catch (PublicationException e) {
                throw new CatalogueFailure(e.code(), "publication refused");
            } catch (Exception e) {
                String step = context.getStepContext().getStepName();
                log.error("catalogue step {} failed: {}", step, SafeFailures.describe(e), SafeFailures.sanitized(e));
                throw new CatalogueFailure("STEP_FAILED", "step " + step);
            }
        };
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.FAILED) {
            for (Throwable failure : execution.getAllFailureExceptions()) {
                if (failure instanceof CatalogueFailure || failure instanceof LeaseLostException) {
                    execution.setExitStatus(new ExitStatus("FAILED", failure.getMessage()));
                    return;
                }
                if (failure instanceof OutputRootException root) {
                    execution.setExitStatus(new ExitStatus("FAILED", root.code().name() + ": output root refused"));
                    return;
                }
                if (failure instanceof PublicationException publication) {
                    execution.setExitStatus(new ExitStatus("FAILED", publication.code() + ": publication refused"));
                    return;
                }
            }
        }
    }
}
