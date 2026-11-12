package org.carddemo.batch.transactionreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.carddemo.batch.execution.OutputRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

class TransactionReportConfigurationTest {
    @TempDir Path directory;

    @Test
    void adapterDelegatesTheExactExecutionWithoutCreatingPaths() {
        var roots = mock(OutputRoots.class);
        var execution = new JobExecution(17L, new JobInstance(7L, TransactionReportConfiguration.JOB),
                new JobParameters());
        Path bound = directory.resolve("not-created");
        when(roots.root(execution)).thenReturn(bound);
        BoundReportRoot adapter = new TransactionReportConfiguration().boundReportRoot(roots);
        assertThat(adapter.root(execution)).isEqualTo(bound).doesNotExist();
        verify(roots).root(execution);
    }
}
