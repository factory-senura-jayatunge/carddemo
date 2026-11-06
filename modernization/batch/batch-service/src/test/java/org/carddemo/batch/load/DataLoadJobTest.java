package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.LoginCheck;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadJobTest extends DataLoadTestBase {

    @DynamicPropertySource
    static void sampleData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    @Test
    void loadsTheFiveSampleFilesIntoEmptyTables() throws Exception {
        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        for (String table : CORE_TABLES) {
            assertThat(count(table)).as(table).isEqualTo(50);
        }
        assertThat(count("core.[transaction]")).isZero();
        Map<String, Long> written = execution.getStepExecutions().stream()
                .collect(Collectors.toMap(StepExecution::getStepName, StepExecution::getWriteCount));
        assertThat(written).containsEntry("loadAccounts", 50L).containsEntry("loadCustomers", 50L)
                .containsEntry("loadCards", 50L).containsEntry("loadCardXref", 50L)
                .containsEntry("loadCategoryBalances", 50L);
        assertThat(DB.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void movesTheGroupIdFromTheZipPosition() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Map<String, Object>> groups = DB.admin().sql(
                "SELECT group_id, addr_zip, COUNT(*) AS n FROM core.account GROUP BY group_id, addr_zip").query().listOfRows();
        assertThat(groups).containsExactly(Map.of("group_id", "A000000000", "addr_zip", "", "n", 50));
    }

    @Test
    void reExportEqualsTheSourceFilesByteForByte() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        CoreRows rows = new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));

        assertSameRecords("acctdata.txt", Layouts.ACCOUNT.length(), rows.accounts(), a -> a.toBytes());
        assertSameRecords("custdata.txt", Layouts.CUSTOMER.length(), rows.customers(), c -> c.toBytes());
        assertSameRecords("carddata.txt", Layouts.CARD.length(), rows.cards(), c -> c.toBytes());
        assertSameRecords("cardxref.txt", Layouts.CARD_XREF.length(), rows.cardXrefs(), x -> x.toBytes());
        assertSameRecords("tcatbal.txt", Layouts.TCAT_BAL.length(), rows.categoryBalances(), t -> t.toBytes());
    }

    private static <T> void assertSameRecords(String file, int length, List<T> rows, Function<T, byte[]> export) {
        List<SourceLine> source = LegacyFileReader.read(SAMPLE_DIR.resolve(file), length);
        assertThat(rows).as(file).hasSameSizeAs(source);
        List<Integer> different = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (!Arrays.equals(source.get(i).record(), export.apply(rows.get(i)))) {
                different.add(source.get(i).lineNumber());
            }
        }
        assertThat(different).as(file + " lines that do not re-export identically").isEmpty();
    }

    @Test
    void refusesTablesThatAlreadyHoldRowsOutsideDemo() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000001", "194.00"));

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution)).contains("D-41").contains("core.account=1");
        assertThat(count("core.account")).isEqualTo(1);
        assertThat(DB.admin().sql("SELECT curr_bal FROM core.account").query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("194.00");
    }

    @Test
    void batchLoginHasOnlyItsGrants() {
        assertThat(new LoginCheck(new DatabaseProvisioner(DB.settings())).run()).isEmpty();
        JdbcClient batch = JdbcClient.create(DB.dataSource(ServiceLogin.BATCH));
        assertThatThrownBy(() -> batch.sql("UPDATE core.account SET curr_bal = 0").update())
                .hasMessageContaining("permission was denied");
        assertThatThrownBy(() -> batch.sql("SELECT TOP 1 tran_id FROM billpay.payment").query().listOfRows())
                .hasMessageContaining("permission was denied");
        assertThat(batch.sql("SELECT COUNT(*) FROM batch.cycle").query(Integer.class).single()).isNotNegative();
    }

}
