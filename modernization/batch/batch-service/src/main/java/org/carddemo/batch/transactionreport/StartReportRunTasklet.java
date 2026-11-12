package org.carddemo.batch.transactionreport;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * The first step: the run row becomes RUNNING for this execution, in a fenced transaction of its
 * own, so no later failure can roll the row back to REQUESTED. A restart runs it again.
 *
 * <p>One COMPLETED row is not refused: the one an earlier execution of this instance completed
 * when the restart carries that execution's finished publication. That execution failed after
 * its completing commit; the step then writes nothing and {@code print} verifies the file.
 */
final class StartReportRunTasklet implements Tasklet {
    private final ReportRunRows rows;

    StartReportRunTasklet(ReportRunRows rows) {
        this.rows = rows;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext context) {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        long runId = TransactionReportConfiguration.runId(job);
        if (rows.markRunning(runId, job.getId())) {
            return RepeatStatus.FINISHED;
        }
        if (ReportPublication.finished(job.getExecutionContext(), runId)
                && rows.completedByEarlier(runId, job.getJobInstance().getId(), job.getId())) {
            return RepeatStatus.FINISHED;
        }
        throw new CatalogueFailure("REPORT_RUN_NOT_RUNNABLE", "report run " + runId);
    }
}
