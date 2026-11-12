package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLException;

import org.carddemo.batch.execution.ExecutionTagException;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.UncategorizedSQLException;

class SafeTaskletTest {

    private static final String VALUE = "quoted-statement-value";
    private static final String SENTINEL = "var-sentinel-q7z";
    private static final String PATH = "/srv/" + SENTINEL + "/out/0123/rejects/17-1/DALYREJS.txt";

    private static ChunkContext context() {
        JobExecution job = new JobExecution(1L, new JobInstance(1L, "postDailyTransactionsJob"), new JobParameters());
        StepExecution step = new StepExecution(1L, "post", job);
        return new ChunkContext(new StepContext(step));
    }

    private static Throwable failure(Tasklet tasklet) {
        ChunkContext context = context();
        StepContribution contribution = context.getStepContext().getStepExecution().createStepContribution();
        try {
            new SafeTasklet(tasklet).execute(contribution, context);
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("the tasklet did not fail");
    }

    @Test
    void anSqlFailureLeavesWithTheStepAndTheErrorNumberOnly() {
        SQLException driver = new SQLException("Truncated value: '" + VALUE + "'", "22001", 2628);
        Throwable t = failure((c, x) -> {
            throw new UncategorizedSQLException("post", "UPDATE x SET y = ?", driver);
        });
        assertThat(t).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage("STEP_FAILED: step post failed with SQL error 2628");
        assertThat(t.getSuppressed()).isEmpty();
    }

    @Test
    void anotherFailureLeavesWithItsClassName() {
        Throwable t = failure((c, x) -> {
            throw new IllegalStateException(VALUE);
        });
        assertThat(t).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage("STEP_FAILED: step post failed (IllegalStateException)");
    }

    @Test
    void aSessionTagFailureKeepsItsCode() {
        Throwable t = failure((c, x) -> {
            throw new ExecutionTagException(ExecutionTagException.NOT_RESTORED,
                    "the session's prior value did not read back; the connection is evicted", null);
        });
        assertThat(t).isInstanceOf(CycleJobException.class).hasNoCause().hasMessage(
                "CONNECTION_TAG_NOT_RESTORED: the session's prior value did not read back; the connection is evicted");
    }

    /** Contract section 4.10, Messages outside the root: the library's text is never forwarded. */
    @ParameterizedTest
    @EnumSource(OutputRootException.Code.class)
    @ExtendWith(OutputCaptureExtension.class)
    void anOutputRootFailureLeavesWithItsCodeAndAFixedReasonOnly(OutputRootException.Code code, CapturedOutput output) {
        Throwable t = failure((c, x) -> {
            throw new OutputRootException(code, PATH + " has group or other permissions", new IOException(PATH));
        });
        assertThat(t).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage(code.name() + ": step post failed: output root refused");
        assertThat(((CycleJobException) t).code()).isEqualTo(code.name());
        assertThat(output.getAll()).contains("step post failed").doesNotContain(SENTINEL);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aPublicationFailureLeavesWithItsCodeAndAFixedReasonOnly(CapturedOutput output) {
        Throwable byText = failure((c, x) -> {
            throw new PublicationException("the working path " + PATH + " is not this execution's");
        });
        assertThat(byText).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage("PUBLICATION_MISMATCH: step post failed: publication refused");
        Throwable byReason = failure((c, x) -> {
            throw new PublicationException(PublicationException.Reason.FINAL_NOT_EXACT, 1, 2);
        });
        assertThat(byReason).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage("PUBLICATION_MISMATCH: step post failed: publication refused");
        assertThat(output.getAll()).contains("step post failed: PUBLICATION_MISMATCH FINAL_NOT_EXACT")
                .doesNotContain(SENTINEL);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFileSystemFailureLeavesWithTheTypeOfItsCauseOnly(CapturedOutput output) {
        Throwable t = failure((c, x) -> {
            throw new UncheckedIOException(new NoSuchFileException(PATH));
        });
        assertThat(t).isInstanceOf(CycleJobException.class).hasNoCause()
                .hasMessage("STEP_FAILED: step post failed (NoSuchFileException)");
        assertThat(output.getAll()).contains("UncheckedIOException < NoSuchFileException").doesNotContain(SENTINEL);
    }

    @Test
    void aCycleJobExceptionAndAnErrorPassUnchanged() {
        CycleJobException own = new CycleJobException("DAILY_AMOUNT_INVALID", "daily record 20 has no valid DALYTRAN-AMT");
        assertThat(failure((c, x) -> {
            throw own;
        })).isSameAs(own);
        AssertionError error = new AssertionError("an error");
        assertThatThrownBy(() -> new SafeTasklet((c, x) -> {
            throw error;
        }).execute(null, context())).isSameAs(error);
    }
}
