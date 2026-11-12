package org.carddemo.batch.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.carddemo.batch.execution.LeaseLostException;
import org.carddemo.batch.execution.PublicationException;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;

class CatalogueFailuresTest {
    private static JobExecution job() {
        return new JobExecution(17L, new JobInstance(7L, "categoryBalanceListingJob"), new JobParameters());
    }

    private static void execute(Tasklet tasklet) throws Exception {
        var step = new StepExecution(1L, "list", job());
        CatalogueFailures.safe(tasklet).execute(step.createStepContribution(), new ChunkContext(new StepContext(step)));
    }

    @Test
    void leaseLossPassesThroughUnchanged() {
        var lost = new LeaseLostException(17);
        assertThatThrownBy(() -> execute((contribution, context) -> { throw lost; }))
                .isSameAs(lost).hasMessageStartingWith("LEASE_LOST:");
    }

    @Test
    void leaseLossKeepsItsCodeInTheJobExitDescription() {
        var job = job();
        job.setStatus(BatchStatus.FAILED);
        job.addFailureException(new LeaseLostException(17));
        new CatalogueFailures().afterJob(job);
        assertThat(job.getExitStatus().getExitCode()).isEqualTo("FAILED");
        assertThat(job.getExitStatus().getExitDescription()).startsWith("LEASE_LOST:").doesNotContain("STEP_FAILED");
    }

    @Test
    void anUntrustedFailureStillLosesItsMessageAndCause() {
        assertThatThrownBy(() -> execute((contribution, context) -> {
            throw new IllegalArgumentException("sentinel-sensitive-value");
        })).isInstanceOf(CatalogueFailure.class).hasMessage("STEP_FAILED: step list").hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(value = OutputRootException.Code.class, names = {"OUTPUT_ROOT_INVALID", "OUTPUT_ROOT_MISMATCH"})
    void outputRootFailuresKeepOnlyTheirSafeCodeThroughTheJobExit(OutputRootException.Code code) {
        Throwable failure = catchThrowable(() -> execute((contribution, context) -> {
            throw new OutputRootException(code, "sentinel-sensitive-path-or-config",
                    new IllegalArgumentException("sentinel-sensitive-cause"));
        }));
        assertThat(failure).isInstanceOf(CatalogueFailure.class)
                .hasMessage(code.name() + ": output root refused").hasNoCause();
        var job = job();
        job.setStatus(BatchStatus.FAILED);
        job.addFailureException(failure);
        new CatalogueFailures().afterJob(job);
        assertThat(job.getExitStatus().getExitDescription()).isEqualTo(code.name() + ": output root refused");
    }

    @ParameterizedTest
    @EnumSource(value = OutputRootException.Code.class, names = {"OUTPUT_ROOT_INVALID", "OUTPUT_ROOT_MISMATCH"})
    void beforeCommitRootFailuresKeepOnlyTheirSafeCodeInTheJobExit(OutputRootException.Code code) {
        var job = job();
        job.setStatus(BatchStatus.FAILED);
        job.addFailureException(new OutputRootException(code, "sentinel-sensitive-path-or-config"));
        new CatalogueFailures().afterJob(job);
        assertThat(job.getExitStatus().getExitDescription()).isEqualTo(code.name() + ": output root refused");
    }

    @Test
    void publicationFailureKeepsItsCodeButNotItsPathThroughTheWrapper() {
        Throwable failure = catchThrowable(() -> execute((contribution, context) -> {
            throw new PublicationException("sentinel-working-path");
        }));
        assertThat(failure).isInstanceOf(CatalogueFailure.class)
                .hasMessage("PUBLICATION_MISMATCH: publication refused").hasNoCause();
        var job = job();
        job.setStatus(BatchStatus.FAILED);
        job.addFailureException(failure);
        new CatalogueFailures().afterJob(job);
        assertThat(job.getExitStatus().getExitDescription()).isEqualTo("PUBLICATION_MISMATCH: publication refused");
    }

    @Test
    void aDirectPublicationFailureKeepsOnlyItsCodeInTheJobExit() {
        var job = job();
        job.setStatus(BatchStatus.FAILED);
        job.addFailureException(new PublicationException("sentinel-working-path"));
        new CatalogueFailures().afterJob(job);
        assertThat(job.getExitStatus().getExitDescription()).isEqualTo("PUBLICATION_MISMATCH: publication refused");
    }
}
