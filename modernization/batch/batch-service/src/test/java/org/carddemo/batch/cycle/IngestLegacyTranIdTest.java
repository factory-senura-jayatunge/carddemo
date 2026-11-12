package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/** ingestDailyFileJob with BR-TRNA-003 legacy: IDs are highest + 1, so no file ID is reserved. */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.fidelity.BR-TRNA-003=legacy"})
class IngestLegacyTranIdTest extends CycleJobTestBase {

    @Test
    void aFileIdInTheFixedModeRangeIsStagedAndClaimed() {
        List<String> lines = sampleDaily();
        lines.set(6, withId(lines.get(6), "9000000000000123"));
        dailyFile("2101-04-01", lines);
        long cycle = openCycle("2101-04-01");

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ? AND seq = 7 "
                + "AND tran_id = '9000000000000123'", cycle)).isEqualTo(1);
    }
}
