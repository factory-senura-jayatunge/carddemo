package org.carddemo.batch.cycle;

import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.sql.SQLException;

import org.carddemo.batch.execution.ExecutionTagException;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.execution.SafeFailures;
import org.carddemo.platform.outputroot.OutputRootException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Wraps every tasklet of the cycle jobs. Spring Batch writes a step's failure with its whole cause
 * chain into the step's and the job's EXIT_MESSAGE and into the log, and a driver message can
 * quote a statement value (SQL Server error 2628 prints the truncated value). So a failure that
 * is not already a {@link CycleJobException} leaves as one without a cause, naming the step:
 * a {@link PublicationException} or an {@link OutputRootException} keeps only its code and a
 * fixed reason, because their messages can name a path below CARDDEMO_VAR_DIR (contract section
 * 4.10, Messages outside the root); an {@link ExecutionTagException} keeps its code and detail,
 * which name at most an SQL error number or the class of its cause; any other failure names the
 * SQL error number, or the class of the exception (of its cause for a file system failure) when
 * no SQL error is in the chain, and its chain is logged as {@link SafeFailures} describes it.
 */
final class SafeTasklet implements Tasklet {

    static final String STEP_FAILED = "STEP_FAILED";
    static final String OUTPUT_ROOT_REFUSED = "output root refused";
    static final String PUBLICATION_REFUSED = "publication refused";

    private static final Logger log = LoggerFactory.getLogger(SafeTasklet.class);
    private static final int MAX_DEPTH = 16;

    private final Tasklet delegate;

    SafeTasklet(Tasklet delegate) {
        this.delegate = delegate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        try {
            return delegate.execute(contribution, chunkContext);
        } catch (CycleJobException e) {
            throw e;
        } catch (PublicationException e) {
            String step = chunkContext.getStepContext().getStepName();
            // The reason is a constant of a closed set; the exception's own text is never used.
            log.error("step {} failed: {} {}", step, e.code(), e.reason() == null ? "" : e.reason().name());
            throw new CycleJobException(e.code(), "step " + step + " failed: " + PUBLICATION_REFUSED);
        } catch (ExecutionTagException e) {
            // Its message names an SQL error number at most, and its cause is already sanitized.
            log.error("step {} failed: {}", chunkContext.getStepContext().getStepName(), e.getMessage());
            throw new CycleJobException(e.code(), e.detail());
        } catch (OutputRootException e) {
            String step = chunkContext.getStepContext().getStepName();
            log.error("step {} failed: {}", step, e.code().name());
            throw new CycleJobException(e.code().name(), "step " + step + " failed: " + OUTPUT_ROOT_REFUSED);
        } catch (Exception e) {
            String step = chunkContext.getStepContext().getStepName();
            log.error("step {} failed: {}", step, SafeFailures.describe(e), SafeFailures.sanitized(e));
            throw new CycleJobException(STEP_FAILED, "step " + step + " failed " + detail(e));
        }
    }

    private static String detail(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return "with SQL error " + sql.getErrorCode();
            }
        }
        boolean fileSystem = e instanceof UncheckedIOException || e instanceof DirectoryIteratorException;
        Throwable named = fileSystem && e.getCause() != null ? e.getCause() : e;
        return "(" + named.getClass().getSimpleName() + ")";
    }
}
