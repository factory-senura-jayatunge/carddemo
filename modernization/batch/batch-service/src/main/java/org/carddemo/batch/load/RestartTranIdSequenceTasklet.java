package org.carddemo.batch.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Last step of dataLoadJob: {@code core.tran_id_seq} restarts at the start of
 * the reserved range of fixed-mode IDs (D-59), or above the highest ID a table
 * already holds in that range, so no ID is issued twice. Only 16-digit IDs
 * from {@value #RANGE_START} up are in the range; any other legacy ID is valid
 * and left alone. Waiting online adds count too: the load does not empty
 * {@code core.daily_tran}. On the sample nothing is in the range and the
 * sequence restarts at {@value #RANGE_START}.
 */
final class RestartTranIdSequenceTasklet implements Tasklet {

    static final long RANGE_START = 9_000_000_000_000_000L;
    static final long RANGE_END = 9_999_999_999_999_999L;

    private static final Logger log = LoggerFactory.getLogger(RestartTranIdSequenceTasklet.class);

    private final JdbcClient db;

    RestartTranIdSequenceTasklet(JdbcClient db) {
        this.db = db;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String highest = db.sql("""
                SELECT MAX(tran_id)
                FROM (SELECT tran_id FROM core.[transaction] UNION ALL SELECT tran_id FROM core.daily_tran) AS ids
                WHERE LEN(tran_id) = 16 AND tran_id NOT LIKE '%[^0-9]%' AND tran_id >= '9000000000000000'
                """).query(String.class).optional().orElse(null);
        long next = highest == null ? RANGE_START : Long.parseLong(highest) + 1;
        if (next > RANGE_END) {
            throw new DataLoadException("TRAN_ID_EXHAUSTED: the highest transaction ID in the reserved range is "
                    + highest + "; core.tran_id_seq ends at " + RANGE_END + " and does not wrap");
        }
        // ALTER SEQUENCE takes no parameters; next is a long computed above.
        db.sql("ALTER SEQUENCE core.tran_id_seq RESTART WITH " + next).update();
        log.info("restartTranIdSequence: core.tran_id_seq restarts at {}", next);
        return RepeatStatus.FINISHED;
    }
}
