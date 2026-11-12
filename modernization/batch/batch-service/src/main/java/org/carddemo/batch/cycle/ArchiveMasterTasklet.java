package org.carddemo.batch.cycle;

import javax.sql.DataSource;

import org.carddemo.batch.execution.ExecutionConnectionTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * archiveMasterJob's {@code archive} (TRANBKP): every row of the master leaves it, and the rows
 * stay as the backup, so nothing can be lost between backup and reset (DEF-JTBKP-001). A repeat
 * finds the master empty and keeps the count of the first run. The UPDATE can wait on an online
 * writer's lock, so both UPDATEs run with the session tagged with the job execution ID
 * ({@link ExecutionConnectionTag}, batch-execution-contract.md section 4.5).
 */
final class ArchiveMasterTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ArchiveMasterTasklet.class);

    private final JdbcClient db;
    private final DataSource dataSource;

    ArchiveMasterTasklet(JdbcClient db, DataSource dataSource) {
        this.db = db;
        this.dataSource = dataSource;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        Long executionId = contribution.getStepExecution().getJobExecution().getId();
        int archived = ExecutionConnectionTag.within(dataSource, executionId, () -> {
            int n = db.sql("UPDATE core.[transaction] SET in_master = 0 WHERE in_master = 1").update();
            db.sql("UPDATE batch.cycle SET archived_count = COALESCE(archived_count, :n) WHERE cycle_id = :id")
                    .param("n", n).param("id", run.cycleId()).update();
            return n;
        });
        contribution.incrementWriteCount(archived);
        log.info("archive: cycle {} moved {} transactions out of the master", run.cycleId(), archived);
        return RepeatStatus.FINISHED;
    }
}
