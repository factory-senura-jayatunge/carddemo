package org.carddemo.batch.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Last step of dataLoadJob: {@code core.tran_id_seq} restarts above the highest
 * transaction ID of the master, so fixed-mode IDs (D-59) never repeat a loaded
 * one. On the sample the master is empty and the sequence restarts at 1.
 */
final class RestartTranIdSequenceTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(RestartTranIdSequenceTasklet.class);

    private final JdbcClient db;

    RestartTranIdSequenceTasklet(JdbcClient db) {
        this.db = db;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long highest = db.sql("""
                SELECT COALESCE(MAX(CAST(tran_id AS BIGINT)), 0) FROM core.[transaction]
                WHERE in_master = 1 AND tran_id NOT LIKE '%[^0-9]%'
                """).query(Long.class).single();
        long next = highest + 1;
        // ALTER SEQUENCE takes no parameters; next is a long computed above.
        db.sql("ALTER SEQUENCE core.tran_id_seq RESTART WITH " + next).update();
        log.info("restartTranIdSequence: core.tran_id_seq restarts at {}", next);
        return RepeatStatus.FINISHED;
    }
}
