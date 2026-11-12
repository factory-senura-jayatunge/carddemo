package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.carddemo.batch.execution.PublicationException;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.transaction.TransactionSystemException;

/**
 * The job's exit description (contract section 4.10, Messages outside the root): an output root
 * or publication failure that left a step outside SafeTasklet, as the fence's commit check does,
 * is described by its code, the step and a fixed reason, never by its message.
 */
class CycleExitDescriptionsTest {

    private static final String SENTINEL = "var-sentinel-q7z";
    private static final String PATH = "/srv/" + SENTINEL + "/out/0123/dailytran/archive";

    private static JobExecution failedJob() {
        JobExecution job = new JobExecution(17L, new JobInstance(7L, "ingestDailyFileJob"), new JobParameters());
        job.setStatus(BatchStatus.FAILED);
        job.setExitStatus(ExitStatus.FAILED);
        return job;
    }

    private static JobExecution failedIn(String stepName, Throwable failure) {
        JobExecution job = failedJob();
        StepExecution step = new StepExecution(1L, stepName, job);
        if (!job.getStepExecutions().contains(step)) {
            job.addStepExecution(step);
        }
        step.setStatus(BatchStatus.FAILED);
        step.addFailureException(failure);
        return job;
    }

    private static String described(JobExecution job) {
        new CycleExitDescriptions().afterJob(job);
        return job.getExitStatus().getExitDescription();
    }

    @ParameterizedTest
    @EnumSource(OutputRootException.Code.class)
    void aFenceFailureOfAStepIsItsCodeTheStepAndAFixedReason(OutputRootException.Code code) {
        JobExecution job = failedIn("stage", new OutputRootException(code, PATH + " names another database",
                new IOException(PATH)));

        assertThat(described(job)).isEqualTo(code.name() + ": step stage failed: output root refused");
        assertThat(job.getExitStatus().getExitCode()).isEqualTo("FAILED");
    }

    @Test
    void anOutputRootFailureDeeperInTheChainIsFoundAndStillNamesNoPath() {
        JobExecution job = failedIn("stage", new TransactionSystemException("commit failed: " + PATH,
                new OutputRootException(OutputRootException.Code.OUTPUT_ROOT_MISMATCH, PATH)));

        assertThat(described(job)).isEqualTo("OUTPUT_ROOT_MISMATCH: step stage failed: output root refused");
    }

    @Test
    void aJobLevelOutputRootFailureHasNoStep() {
        JobExecution job = failedJob();
        job.addFailureException(new OutputRootException(OutputRootException.Code.OUTPUT_PERMISSIONS, PATH));

        assertThat(described(job)).isEqualTo("OUTPUT_PERMISSIONS: output root refused");
    }

    @Test
    void aPublicationFailureOutsideSafeTaskletIsItsCodeTheStepAndAFixedReason() {
        JobExecution job = failedIn("exportRejects", new PublicationException("the working path " + PATH));

        assertThat(described(job)).isEqualTo("PUBLICATION_MISMATCH: step exportRejects failed: publication refused");
    }

    @Test
    void aCycleJobExceptionKeepsItsOwnMessage() {
        JobExecution job = failedIn("stage", new CycleJobException("DAILY_FILE_NOT_FOUND",
                "no daily file 2101-03-01.txt in the inbound folder"));

        assertThat(described(job)).isEqualTo("DAILY_FILE_NOT_FOUND: no daily file 2101-03-01.txt in the inbound folder");
    }

    @Test
    void anotherFailureLeavesTheDescriptionAlone() {
        JobExecution job = failedIn("stage", new IllegalStateException(PATH));

        assertThat(described(job)).isEmpty();
    }

    @Test
    void aCompletedJobWithRejectsExitsCompletedWithRejects() {
        JobExecution job = new JobExecution(17L, new JobInstance(7L, "postDailyTransactionsJob"), new JobParameters());
        job.setStatus(BatchStatus.COMPLETED);
        job.getExecutionContext().put(ExecutionOutputs.REJECT_COUNT, 38L);

        new CycleExitDescriptions().afterJob(job);

        assertThat(job.getExitStatus().getExitCode()).isEqualTo(ExecutionOutputs.COMPLETED_WITH_REJECTS);
    }
}
