package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;

/** archiveMasterJob (TRANBKP, DEF-JTBKP-001): the master empties and its rows stay as the backup. */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7"})
class ArchiveMasterJobTest extends CycleJobTestBase {

    static final String DATE = "2101-05-01";

    @Test
    void movesEveryMasterRowOutOfTheMasterAndCountsThem() {
        DB.fixtures().transaction("0000000000000001", "0000000000000000", "1.00")
                .transaction("0000000000000002", "0000000000000000", "2.00")
                .transaction("0000000000000003", "0000000000000000", "3.00");
        DB.admin().sql("UPDATE core.[transaction] SET in_master = 0 WHERE tran_id = '0000000000000003'").update();
        long cycle = openCycle(DATE);

        JobExecution run = launch("archiveMasterJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(IngestDailyFileJobTest.step(run, "archive").getWriteCount()).isEqualTo(2);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE in_master = 1")).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction]")).isEqualTo(3);
        assertThat(count("SELECT CAST(archived_count AS BIGINT) FROM batch.cycle WHERE cycle_id = ?", cycle))
                .isEqualTo(2);
    }

    @Test
    void anotherAttemptFindsTheMasterEmptyAndKeepsTheFirstCount() {
        DB.fixtures().transaction("0000000000000001", "0000000000000000", "1.00");
        long cycle = openCycle(DATE);
        launch("archiveMasterJob", cycle);
        DB.admin().sql("UPDATE batch.cycle SET attempt = 2 WHERE cycle_id = :c").param("c", cycle).update();

        JobExecution again = launch("archiveMasterJob", cycle);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(again.getJobParameters().getLong("attempt")).isEqualTo(2L);
        assertThat(IngestDailyFileJobTest.step(again, "archive").getWriteCount()).isZero();
        assertThat(count("SELECT CAST(archived_count AS BIGINT) FROM batch.cycle WHERE cycle_id = ?", cycle))
                .isEqualTo(1);
    }
}
