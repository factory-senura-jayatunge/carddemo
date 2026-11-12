package org.carddemo.batch.transactionreport;

import java.security.MessageDigest;
import java.util.function.Consumer;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The report step. Its first transaction builds the report into this execution's working file
 * and commits the intent; only the next transaction publishes those bytes and completes the run
 * row (contract sections 4.3 and 4.8). A restart that finds an intent builds nothing; when an
 * earlier execution of the instance already completed the row with it, the restart only verifies
 * the row's hash and the published file and leaves the row as it is.
 */
final class TransactionReportTasklet implements Tasklet {

    /** Legacy order (DEF-TRPT-004): the SORT by card number of the master in transaction ID order. */
    static final String BY_CARD = "t.card_num, t.tran_id";
    /** Fixed order (DEF-TRPT-004, D-47): by account, then as legacy inside it. */
    static final String BY_ACCOUNT = "x.acct_id, t.card_num, t.tran_id";

    private final JdbcClient db;
    private final JobRepository repository;
    private final OutputPublication outputs;
    private final BoundReportRoot roots;
    private final ReportRunRows rows;
    private final FidelitySwitches switches;

    /**
     * Runs on the step's thread after the transaction that completed the run row commits and
     * before the job repository records the step's end; a test stops an owner there.
     */
    volatile Consumer<JobExecution> afterCompletedCommit = job -> { };

    TransactionReportTasklet(JdbcClient db, JobRepository repository, OutputPublication outputs,
            BoundReportRoot roots, ReportRunRows rows, FidelitySwitches switches) {
        this.db = db;
        this.repository = repository;
        this.outputs = outputs;
        this.roots = roots;
        this.rows = rows;
        this.switches = switches;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext context) throws Exception {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        long runId = TransactionReportConfiguration.runId(job);
        var intent = OutputPublication.intent(job.getExecutionContext(), ReportPublication.PREFIX);
        if (intent.isPresent()) {
            outputs.beginPublish(job);
        }
        // The publish renewal must be the first SQL statement, before the instance fence in roots.root(job).
        var publication = new ReportPublication(roots.root(job), runId);
        if (intent.isPresent()) {
            var captured = intent.get();
            ExecutionContext jobContext = job.getExecutionContext();
            if (ReportPublication.finished(jobContext, runId)
                    && rows.completedByEarlier(runId, job.getJobInstance().getId(), job.getId())) {
                // A row completed before output_sha256 existed has none; the context alone is checked then.
                byte[] intended = ReportRunRows.sha256Bytes(captured.sha256());
                if (rows.completedSha256(runId).filter(stored -> !MessageDigest.isEqual(stored, intended))
                        .isPresent()) {
                    ReportPublication.mismatch();
                }
                outputs.cleanUp(job, ReportPublication.PREFIX, publication.folder(), publication::workName);
                publication.verifyPublished(captured);
                return RepeatStatus.FINISHED;
            }
            var work = publication.work(captured.workName());
            outputs.cleanUp(job, ReportPublication.PREFIX, publication.folder(), publication::workName);
            OutputPublication.publishFile(work, publication.target(), captured.sha256());
            // In the transaction that records the publication: only COMPLETED makes the file readable.
            // A first publication and a restart after a rolled-back completion (ALREADY_PUBLISHED)
            // both reach here, and both store the hash the target was just verified against.
            rows.markCompleted(runId, job.getId(), captured.sha256());
            // Spring Batch saves the job context between steps, so a key of a rolled-back completion
            // would otherwise reach the restart as a finished publication.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCompletedCommit.accept(job);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        jobContext.remove(ReportPublication.OUTPUT);
                    }
                }
            });
            jobContext.putString(ReportPublication.OUTPUT, ReportPublication.relativePath(runId));
            repository.updateExecutionContext(job);
            return RepeatStatus.FINISHED;
        }
        ReportRunRows.Range range = rows.running(runId, job.getId());
        if (publication.targetExists()) {
            ReportPublication.mismatch();
        }
        outputs.cleanUp(job, ReportPublication.PREFIX, publication.folder(), publication::workName);
        boolean skipOutOfRange = switches.isFixed(BatchSwitches.DEF_TRPT_001);
        boolean byAccount = switches.isFixed(BatchSwitches.DEF_TRPT_004);
        String work = publication.workName(job.getId());
        long details;
        long lines;
        try (var out = publication.create(job.getId())) {
            var writer = new TransactionReportWriter(out, range.start().toString(), range.end().toString(),
                    skipOutOfRange, byAccount);
            // One statement under READ_COMMITTED_SNAPSHOT reads one version of the master and of every
            // row its lookups use, independent of concurrent posting and online writes, with no window.
            db.sql("""
                    SELECT t.proc_date, t.tran_id, t.card_num, x.acct_id, t.type_cd, tt.description AS type_desc,
                           t.cat_cd, tc.description AS cat_desc, t.source, t.amount
                    FROM core.[transaction] AS t
                    LEFT JOIN core.card_xref AS x ON x.card_num = t.card_num
                    LEFT JOIN ref.tran_type AS tt ON tt.type_cd = t.type_cd
                    LEFT JOIN ref.tran_category AS tc ON tc.type_cd = t.type_cd AND tc.cat_cd = t.cat_cd
                    WHERE t.in_master = 1 AND t.proc_date BETWEEN :start AND :end
                    ORDER BY\s""" + (byAccount ? BY_ACCOUNT : BY_CARD))
                    .param("start", range.start()).param("end", range.end())
                    .query(rs -> {
                        contribution.incrementReadCount();
                        writer.accept(row(rs.getObject("proc_date", java.time.LocalDate.class).toString(),
                                rs.getString("tran_id"), rs.getString("card_num"), rs.getString("acct_id"),
                                rs.getString("type_cd"), rs.getString("type_desc"), rs.getString("cat_cd"),
                                rs.getString("cat_desc"), rs.getString("source"), rs.getBigDecimal("amount")));
                    });
            writer.finish();
            details = writer.details();
            lines = writer.lines();
        }
        String hash = publication.hashWork(work);
        outputs.commitIntent(job, ReportPublication.PREFIX, new OutputPublication.Intent(work, hash));
        contribution.incrementWriteCount(lines);
        contribution.getStepExecution().getExecutionContext().putLong("carddemo.tranrept.details", details);
        return RepeatStatus.CONTINUABLE;
    }

    /**
     * A row whose lookup found nothing fails the report, as the program abends (INV-016,
     * ERR-TRPT-004), in the program's lookup order. The failure names no value.
     */
    static ReportRow row(String procDate, String tranId, String cardNum, String acctId, String typeCd,
            String typeDesc, String catCd, String catDesc, String source, java.math.BigDecimal amount) {
        if (acctId == null) {
            throw new CatalogueFailure("REPORT_REFERENCE_MISSING", "card cross-reference");
        }
        if (typeDesc == null) {
            throw new CatalogueFailure("REPORT_REFERENCE_MISSING", "transaction type");
        }
        if (catDesc == null) {
            throw new CatalogueFailure("REPORT_REFERENCE_MISSING", "transaction category");
        }
        return new ReportRow(procDate, tranId, cardNum, acctId, typeCd, typeDesc, catCd, catDesc, source, amount);
    }
}
