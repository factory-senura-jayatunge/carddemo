package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.batch.statement.StatementRunFixtures;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.AfterEach;
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
                .restartTranIdSequence(9_000_000_000_000_078L);

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
        assertThat(DB.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .isEqualTo(9_000_000_000_000_000L);
    }

    private static final long[] STATEMENT_EXECUTIONS = {-31, -32, -33, -34, -35};

    @AfterEach
    void dropStatementRuns() {
        StatementRunFixtures.drop(DB.admin(), STATEMENT_EXECUTIONS);
    }

    /**
     * core.transaction is emptied, so every COMPLETED statement run is SUPERSEDED and every
     * unfinished one INVALIDATED, in the load's transaction (batch.md, "Runs, restart and rerun").
     */
    @Test
    void aReloadSupersedesCompletedStatementRunsAndInvalidatesUnfinishedOnes() throws Exception {
        String[] statuses = {"COMPLETED", "RUNNING", "FAILED", "PUBLISHING", "SUPERSEDED"};
        String[] expected = {"SUPERSEDED", "INVALIDATED", "INVALIDATED", "INVALIDATED", "SUPERSEDED"};
        long[] runs = new long[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            long cycle = DB.admin().sql("INSERT INTO batch.cycle (business_date) OUTPUT inserted.cycle_id VALUES (:d)")
                    .param("d", java.time.LocalDate.of(2101, 8, i + 1)).query(Long.class).single();
            runs[i] = StatementRunFixtures.insert(DB.admin(), cycle, statuses[i], STATEMENT_EXECUTIONS[i]);
        }

        JobExecution load = run();

        assertThat(load.getStatus()).as(failures(load)).isEqualTo(BatchStatus.COMPLETED);
        for (int i = 0; i < statuses.length; i++) {
            assertThat(StatementRunFixtures.status(DB.admin(), runs[i])).as(statuses[i]).isEqualTo(expected[i]);
        }
    }
}
