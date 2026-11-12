package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The reserved range of fixed-mode transaction IDs (D-59) and the load's restart of core.tran_id_seq. */
class RestartTranIdSequenceTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final String CARD = "4111111111111111";

    private final JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
    private final RestartTranIdSequenceTasklet tasklet = new RestartTranIdSequenceTasklet(batch);

    @BeforeEach
    void empty() {
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll().account(CoreFixtures.Account.of("00000000001", "0.00")).customer("000000001")
                .card(CARD, "00000000001", "000000001");
    }

    private static long nextValue() {
        return DB.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single();
    }

    private void restart() {
        tasklet.execute(null, null);
    }

    @Test
    void theSequenceIsTheReservedRangeAndNeverCycles() {
        Map<String, Object> seq = DB.admin().sql("""
                SELECT CAST(minimum_value AS BIGINT) AS min_value, CAST(maximum_value AS BIGINT) AS max_value,
                    is_cycling
                FROM sys.sequences WHERE object_id = OBJECT_ID(N'core.tran_id_seq')""").query().singleRow();
        assertThat(seq).containsEntry("min_value", RestartTranIdSequenceTasklet.RANGE_START)
                .containsEntry("max_value", RestartTranIdSequenceTasklet.RANGE_END)
                .containsEntry("is_cycling", false);
        assertThatThrownBy(() -> DB.admin().sql("ALTER SEQUENCE core.tran_id_seq RESTART WITH 8999999999999999")
                .update()).as("no fixed-mode ID below the range").isInstanceOf(DataAccessException.class);
    }

    @Test
    void withNothingInTheRangeTheFirstFixedIdIsTheStartOfTheRange() {
        List<String> legacy = List.of("0000000000000077", "8999999999999999", "9ABCDEFGHIJKLMNO", "TRAN-ID-00000001",
                "99999999999999X9");
        legacy.forEach(id -> DB.fixtures().transaction(id, CARD, "1.00"));
        DataLoadTestBase.waitingAdd("999999999999999 ");

        restart();

        assertThat(nextValue()).isEqualTo(9_000_000_000_000_000L);
        assertThat(DB.admin().sql("SELECT tran_id FROM core.[transaction] ORDER BY tran_pk").query(String.class).list())
                .as("legacy IDs outside the range are loaded as they are").containsExactlyElementsOf(legacy);
    }

    @Test
    void theRestartIsAboveTheHighestIdInTheRangeOfEitherTable() {
        DB.fixtures().transaction("9000000000000041", CARD, "1.00").transaction("0000000000000099", CARD, "1.00");
        restart();
        assertThat(nextValue()).isEqualTo(9_000_000_000_000_042L);

        DataLoadTestBase.waitingAdd("9000000000000050");
        restart();
        assertThat(nextValue()).isEqualTo(9_000_000_000_000_051L);
    }

    @Test
    void theLastIdOfTheRangeIsIssuedOnceAndTheSequenceDoesNotWrap() {
        DB.fixtures().transaction("9999999999999998", CARD, "1.00");
        restart();
        assertThat(nextValue()).isEqualTo(9_999_999_999_999_999L);
        assertThatThrownBy(RestartTranIdSequenceTest::nextValue).hasMessageContaining("minimum or maximum value");
    }

    @Test
    void aFullRangeFailsWithTranIdExhaustedAndLeavesTheSequenceAlone() {
        DB.fixtures().restartTranIdSequence(9_000_000_000_000_005L).transaction("9999999999999999", CARD, "1.00");

        assertThatThrownBy(this::restart).isInstanceOf(DataLoadException.class)
                .hasMessageContaining("TRAN_ID_EXHAUSTED").hasMessageContaining("9999999999999999");
        assertThat(nextValue()).isEqualTo(9_000_000_000_000_005L);
    }
}
