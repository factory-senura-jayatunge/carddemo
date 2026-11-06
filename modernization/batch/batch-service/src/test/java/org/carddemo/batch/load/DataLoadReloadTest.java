package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The demo profile allows a reload of tables that already hold rows (D-41). */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@ActiveProfiles("demo")
class DataLoadReloadTest extends DataLoadTestBase {

    @DynamicPropertySource
    static void sampleData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    @Test
    void reloadReplacesWhatTheTablesHeld() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "194.00"))
                .customer("000000001")
                .card("4111111111111111", "00000000001", "000000001")
                .transaction("0000000000000077", "4111111111111111", "-12.34")
                .restartTranIdSequence(78);

        JobExecution first = run();
        JobExecution second = run();

        assertThat(first.getStatus()).as(failures(first)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).as(failures(second)).isEqualTo(BatchStatus.COMPLETED);
        for (String table : CORE_TABLES) {
            assertThat(count(table)).as(table).isEqualTo(50);
        }
        assertThat(count("core.[transaction]")).isZero();
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.card WHERE card_num = '4111111111111111'")
                .query(Integer.class).single()).isZero();
        assertThat(DB.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single()).isEqualTo(1L);
    }
}
