package org.carddemo.batch.transactionreport;

import org.carddemo.batch.execution.SafeFailures;
import org.carddemo.batch.execution.SafeTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Records a failed or stopped execution on its run row (data-model.md, "Report runs"): FAILED
 * with JOB_FAILED, or JOB_STOPPED for STOPPED and ABANDONED, in a fenced transaction of the job
 * execution, only while the row is RUNNING for it. When the lease is gone the write fails and
 * recovery records OWNER_LOST instead.
 */
final class ReportRunFailures implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(ReportRunFailures.class);

    private final ReportRunRows rows;
    private final SafeTransactions transactions;

    ReportRunFailures(ReportRunRows rows, PlatformTransactionManager fencing) {
        this.rows = rows;
        this.transactions = new SafeTransactions(fencing);
    }

    static String failureCode(BatchStatus status) {
        return switch (status) {
            case FAILED -> "JOB_FAILED";
            case STOPPED, STOPPING, ABANDONED -> "JOB_STOPPED";
            default -> null;
        };
    }

    @Override
    public void afterJob(JobExecution execution) {
        String code = failureCode(execution.getStatus());
        if (code == null) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> rows.markFailed(execution.getId(), code));
        } catch (RuntimeException e) {
            log.warn("report run of job execution {} not marked {}: {}", execution.getId(), code,
                    SafeFailures.describe(e));
        }
    }
}
