package org.carddemo.batch.categorylisting;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The first transaction commits the intent; only the next transaction may publish those bytes. */
final class CategoryListingTasklet implements Tasklet {
    private final JdbcClient db;
    private final JobRepository repository;
    private final OutputPublication outputs;
    private final BoundListingRoot roots;
    private final FidelitySwitches switches;

    CategoryListingTasklet(JdbcClient db, JobRepository repository, OutputPublication outputs,
            BoundListingRoot roots, FidelitySwitches switches) {
        this.db = db;
        this.repository = repository;
        this.outputs = outputs;
        this.roots = roots;
        this.switches = switches;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext context) throws Exception {
        JobExecution job = contribution.getStepExecution().getJobExecution();
        var intent = OutputPublication.intent(job.getExecutionContext(), ListingPublication.PREFIX);
        if (intent.isPresent()) outputs.beginPublish(job);
        // The publish renewal must be the first SQL statement, before the read-only
        // instance fence in roots.root(job). Preparation has no publication renewal.
        var publication = new ListingPublication(roots.root(job), job.getJobInstance().getId());
        if (intent.isPresent()) {
            var captured = intent.get();
            var work = publication.work(captured.workName());
            outputs.cleanUp(job, ListingPublication.PREFIX, publication.folder(), publication::workName);
            OutputPublication.publishFile(work, publication.target(), captured.sha256());
            job.getExecutionContext().putString(ExecutionOutputs.OUTPUT_PREFIX + "listing",
                    "reports/prtcatbl/" + job.getJobInstance().getId() + "/PRTCATBL.txt");
            repository.updateExecutionContext(job);
            return RepeatStatus.FINISHED;
        }
        if (publication.targetExists()) ListingPublication.mismatch();
        outputs.cleanUp(job, ListingPublication.PREFIX, publication.folder(), publication::workName);
        String work = publication.workName(job.getId());
        boolean signed = switches.isFixed(BatchSwitches.DEF_JPCAT_001);
        // One SELECT under READ_COMMITTED_SNAPSHOT captures one version of the whole input,
        // independent of concurrent posting, without changing tcat_bal or requiring a window.
        var lines = db.sql("""
                SELECT acct_id, type_cd, cat_cd, balance FROM core.tcat_bal
                ORDER BY acct_id, type_cd, cat_cd
                """).query((rs, n) -> CategoryBalanceLine.format(rs.getString("acct_id"), rs.getString("type_cd"),
                        rs.getString("cat_cd"), rs.getBigDecimal("balance"), signed)).list();
        try (var out = publication.create(job.getId())) {
            for (byte[] line : lines) {
                out.write(line);
                contribution.incrementReadCount();
            }
        }
        String hash = publication.hashWork(work);
        outputs.commitIntent(job, ListingPublication.PREFIX, new OutputPublication.Intent(work, hash));
        contribution.incrementWriteCount(lines.size());
        return RepeatStatus.CONTINUABLE;
    }
}
