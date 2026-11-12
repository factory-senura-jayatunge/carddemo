package org.carddemo.batch.transactionreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class ReportRunFailuresTest {
    final ReportRunRows rows = mock(ReportRunRows.class);
    final PlatformTransactionManager fencing = mock(PlatformTransactionManager.class);
    final TransactionStatus status = mock(TransactionStatus.class);
    final ReportRunFailures listener = new ReportRunFailures(rows, fencing);

    JobExecution ended(BatchStatus batchStatus) {
        var job = new JobExecution(11L, new JobInstance(7L, TransactionReportConfiguration.JOB), new JobParameters());
        job.setStatus(batchStatus);
        when(fencing.getTransaction(any())).thenReturn(status);
        return job;
    }

    @Test
    void aFailedExecutionMarksItsRunningRowFailedInAFencedTransaction() {
        listener.afterJob(ended(BatchStatus.FAILED));
        var ordered = inOrder(fencing, rows);
        ordered.verify(fencing).getTransaction(any());
        ordered.verify(rows).markFailed(11L, "JOB_FAILED");
        ordered.verify(fencing).commit(status);
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"STOPPED", "STOPPING", "ABANDONED"})
    void aStoppedExecutionIsRecordedAsStopped(BatchStatus batchStatus) {
        listener.afterJob(ended(batchStatus));
        verify(rows).markFailed(11L, "JOB_STOPPED");
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"COMPLETED", "STARTED", "STARTING", "UNKNOWN"})
    void otherStatusesLeaveTheRowAlone(BatchStatus batchStatus) {
        listener.afterJob(ended(batchStatus));
        verifyNoInteractions(rows);
        verify(fencing, never()).getTransaction(any());
    }

    @Test
    void aWriteThatCannotCommitIsLeftToRecovery() {
        var job = ended(BatchStatus.FAILED);
        when(fencing.getTransaction(any())).thenThrow(new TransactionSystemException("lease lost"));
        listener.afterJob(job);
        verify(rows, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void codesAreTheDataModelsFailureCodes() {
        assertThat(ReportRunFailures.failureCode(BatchStatus.FAILED)).isEqualTo("JOB_FAILED");
        assertThat(ReportRunFailures.failureCode(BatchStatus.STOPPED)).isEqualTo("JOB_STOPPED");
        assertThat(ReportRunFailures.failureCode(BatchStatus.COMPLETED)).isNull();
    }
}
