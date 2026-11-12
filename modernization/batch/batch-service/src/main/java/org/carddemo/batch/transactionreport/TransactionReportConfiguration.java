package org.carddemo.batch.transactionreport;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.batch.catalogue.CatalogueFailures;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * TRANREPT (CBTRN03C) as {@code transactionReportJob}: a report kind job, identified by the
 * {@code batch.report_run} ID, with no window, that only reads the master and writes its run row.
 */
@Configuration(proxyBeanMethods = false)
class TransactionReportConfiguration {
    static final String JOB = "transactionReportJob";
    static final String START_STEP = "start";
    static final String PRINT_STEP = "print";

    @Bean
    BoundReportRoot boundReportRoot(OutputRoots roots) {
        return roots::root;
    }

    @Bean
    ReportRunRows reportRunRows(JdbcClient db) {
        return new ReportRunRows(db);
    }

    @Bean
    TransactionReportTasklet transactionReportTasklet(JdbcClient db, JobRepository repository,
            OutputPublication outputs, BoundReportRoot roots, ReportRunRows rows, FidelitySwitches switches) {
        return new TransactionReportTasklet(db, repository, outputs, roots, rows, switches);
    }

    @Bean
    Job transactionReportJob(JobRepository repository, PlatformTransactionManager tx, ReportRunRows rows,
            TransactionReportTasklet report) {
        var start = new StepBuilder(START_STEP, repository)
                .tasklet(CatalogueFailures.safe(new StartReportRunTasklet(rows)), tx)
                .allowStartIfComplete(true).build();
        var print = new StepBuilder(PRINT_STEP, repository).tasklet(CatalogueFailures.safe(report), tx).build();
        return new JobBuilder(JOB, repository).listener(new CatalogueFailures())
                .listener(new ReportRunFailures(rows, tx)).start(start).next(print).build();
    }

    /** The identifying {@code runId} of the execution (contract section 2.1). */
    static long runId(JobExecution job) {
        Long runId = job.getJobParameters().getLong("runId");
        if (runId == null) {
            throw new CatalogueFailure("REPORT_RUN_NOT_RUNNABLE", "no runId");
        }
        return runId;
    }
}
