package org.carddemo.batch.transactionreport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.carddemo.batch.catalogue.CatalogueFailures;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The print step without a database: a mocked JDBC client streams the given rows, a mocked run
 * row store answers the range, and the publication runs on a real output root.
 */
final class ReportTaskletHarness implements AutoCloseable {
    static final long RUN = 42;
    static final LocalDate START = LocalDate.of(2022, 1, 1);
    static final LocalDate END = LocalDate.of(2022, 7, 6);

    final Path root;
    final ReportPublication publication;
    final JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    final JobRepository repository = mock(JobRepository.class);
    final ExecutionLeases leases = mock(ExecutionLeases.class);
    final LeaseKeeper keeper = new LeaseKeeper(leases);
    final OutputPublication outputs = new OutputPublication(repository, leases, keeper);
    final ReportRunRows rows = mock(ReportRunRows.class);
    final JobExecution job;
    StepExecution step;
    StepContribution contribution;

    ReportTaskletHarness(Path var) {
        root = OutputRoot.ensure(var, new OutputRoot.Instance("tranrept-test", UUID.randomUUID()));
        publication = new ReportPublication(root, RUN);
        job = new JobExecution(1L, new JobInstance(7L, TransactionReportConfiguration.JOB),
                new JobParametersBuilder().addLong("runId", RUN, true).toJobParameters());
        job.setStatus(BatchStatus.STARTED);
        when(repository.getJobExecutions(job.getJobInstance())).thenReturn(List.of(job));
        when(leases.renew(1L)).thenReturn(true);
        when(rows.running(RUN, 1L)).thenReturn(new ReportRunRows.Range(START, END));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    TransactionReportTasklet tasklet(FidelitySwitches switches) {
        return tasklet(switches, ignored -> root);
    }

    TransactionReportTasklet tasklet(FidelitySwitches switches, BoundReportRoot roots) {
        return new TransactionReportTasklet(db, repository, outputs, roots, rows, switches);
    }

    /** The report query streams {@code data} in the given order. */
    void input(ReportRow... data) throws SQLException {
        var statement = db.sql(anyString()).param(anyString(), any()).param(anyString(), any());
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(0);
            for (ReportRow row : data) {
                handler.processRow(resultSet(row));
            }
            return null;
        }).when(statement).query(any(RowCallbackHandler.class));
    }

    static ResultSet resultSet(ReportRow row) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("proc_date", LocalDate.class)).thenReturn(LocalDate.parse(row.procDate()));
        when(rs.getString("tran_id")).thenReturn(row.tranId());
        when(rs.getString("card_num")).thenReturn(row.cardNum());
        when(rs.getString("acct_id")).thenReturn(row.acctId());
        when(rs.getString("type_cd")).thenReturn(row.typeCd());
        when(rs.getString("type_desc")).thenReturn(row.typeDesc());
        when(rs.getString("cat_cd")).thenReturn(row.catCd());
        when(rs.getString("cat_desc")).thenReturn(row.catDesc());
        when(rs.getString("source")).thenReturn(row.source());
        when(rs.getBigDecimal("amount")).thenReturn(row.amount());
        return rs;
    }

    RepeatStatus execute(TransactionReportTasklet tasklet) throws Exception {
        step = new StepExecution(1L, TransactionReportConfiguration.PRINT_STEP, job);
        contribution = step.createStepContribution();
        return CatalogueFailures.safe(tasklet).execute(contribution, new ChunkContext(new StepContext(step)));
    }

    /** Ends the step transaction as the transaction manager would. */
    void finish(int status) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        for (var synchronization : synchronizations) {
            synchronization.afterCompletion(status);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
    }

    void commit() {
        finish(TransactionSynchronization.STATUS_COMMITTED);
    }

    /**
     * What an earlier execution leaves when it completed the run row and then failed before the job
     * repository recorded its end: its committed intent, the published file and the output key.
     */
    String finishedEarlier(byte[] bytes) throws Exception {
        String work = captured(bytes);
        OutputPublication.publishFile(publication.work(work), publication.target(),
                job.getExecutionContext().getString(ReportPublication.HASH));
        job.getExecutionContext().putString(ReportPublication.OUTPUT, ReportPublication.relativePath(RUN));
        return work;
    }

    /** A committed intent of execution 9, as an earlier transaction would leave it. */
    String captured(byte[] bytes) throws Exception {
        try (var out = publication.create(9L)) {
            out.write(bytes);
        }
        String work = publication.workName(9L);
        job.getExecutionContext().putString(ReportPublication.WORK, work);
        job.getExecutionContext().putString(ReportPublication.HASH, publication.hashWork(work));
        return work;
    }

    @Override
    public void close() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
