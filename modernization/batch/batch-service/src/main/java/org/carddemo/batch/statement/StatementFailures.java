package org.carddemo.batch.statement;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.execution.SafeFailures;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.outputroot.OutputRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.tasklet.Tasklet;

/**
 * Wraps statementJob's tasklets and sets its exit description. Spring Batch writes a failure's
 * whole cause chain into EXIT_MESSAGE and the log, and a driver message can quote a value, so a
 * failure leaves a tasklet as a {@link StatementFailure} without a cause: its own code, the code of
 * a {@link LegacyStatementException}, {@link PublicationException} or {@link OutputRootException},
 * or {@code STEP_FAILED} with the SQL error number. A completed job that counted rejects exits
 * {@code COMPLETED_WITH_REJECTS}.
 */
final class StatementFailures implements JobExecutionListener {

    static final String STEP_FAILED = "STEP_FAILED";

    private static final Logger log = LoggerFactory.getLogger(StatementFailures.class);
    private static final int MAX_DEPTH = 16;

    static Tasklet safe(Tasklet delegate) {
        return (contribution, context) -> {
            try {
                return delegate.execute(contribution, context);
            } catch (StatementFailure | LeaseLostException e) {
                throw e;
            } catch (LegacyStatementException e) {
                // Its detail holds counts and record numbers only.
                throw new StatementFailure(e.code(), e.getMessage().substring(e.code().length() + 2));
            } catch (PublicationException e) {
                throw new StatementFailure(e.code(), e.detail());
            } catch (OutputRootException e) {
                String prefix = e.code().name() + ": ";
                throw new StatementFailure(e.code().name(), e.getMessage().startsWith(prefix)
                        ? e.getMessage().substring(prefix.length()) : e.getMessage());
            } catch (Exception e) {
                String step = context.getStepContext().getStepName();
                log.error("statementJob step {} failed: {}", step, SafeFailures.describe(e), SafeFailures.sanitized(e));
                throw new StatementFailure(STEP_FAILED, "step " + step + " failed " + detail(e));
            }
        };
    }

    @Override
    public void afterJob(JobExecution job) {
        if (job.getStatus() == BatchStatus.COMPLETED
                && job.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT) instanceof Number n
                && n.longValue() > 0) {
            job.setExitStatus(new ExitStatus(ExecutionOutputs.COMPLETED_WITH_REJECTS));
        }
        if (job.getStatus() == BatchStatus.FAILED) {
            find(job.getAllFailureExceptions())
                    .ifPresent(m -> job.setExitStatus(new ExitStatus(ExitStatus.FAILED.getExitCode(), m)));
        }
    }

    private static Optional<String> find(List<Throwable> failures) {
        for (Throwable t : failures) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                // A lost lease or an output root failure of the fence comes from the commit, outside safe().
                if (c instanceof StatementFailure || c instanceof LeaseLostException
                        || c instanceof OutputRootException) {
                    return Optional.of(c.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    private static String detail(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return "with SQL error " + sql.getErrorCode();
            }
        }
        return "(" + e.getClass().getSimpleName() + ")";
    }
}
