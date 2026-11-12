package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.RefRows;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.LoginCheck;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.validation.RuleTable;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class DataLoadJobTest extends DataLoadTestBase {

    @Autowired
    FidelitySwitches switches;

    @DynamicPropertySource
    static void sampleData(DynamicPropertyRegistry registry) {
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    @Test
    void loadsTheSampleFilesAndLookupListsIntoEmptyTables() throws Exception {
        JobExecution execution = run();

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        for (String table : CORE_TABLES) {
            assertThat(count(table)).as(table).isEqualTo(50);
        }
        assertThat(count("core.[transaction]")).isZero();
        assertThat(count("ref.tran_type")).isEqualTo(7);
        assertThat(count("ref.tran_category")).isEqualTo(18);
        assertThat(count("ref.disclosure_group")).isEqualTo(51);
        assertThat(count("ref.us_state")).isEqualTo(56);
        assertThat(count("ref.us_state_zip_prefix")).isEqualTo(240);
        assertThat(count("ref.nanp_area_code")).isEqualTo(410);
        Map<String, Long> written = execution.getStepExecutions().stream()
                .collect(Collectors.toMap(StepExecution::getStepName, StepExecution::getWriteCount));
        assertThat(written).containsEntry("loadAccounts", 50L).containsEntry("loadCustomers", 50L)
                .containsEntry("loadCards", 50L).containsEntry("loadCardXref", 50L)
                .containsEntry("loadCategoryBalances", 50L).containsEntry("loadTransactionTypes", 7L)
                .containsEntry("loadCategories", 18L).containsEntry("loadDisclosureGroups", 51L)
                .containsEntry("loadLookups", 56L + 240L + 410L)
                .containsEntry("loadPendingAuthorizations", 21L + 202L);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(
                "clearCoreTables", "loadAccounts", "loadCustomers", "loadCards", "loadCardXref",
                "loadCategoryBalances", "loadTransactionTypes", "loadCategories", "loadDisclosureGroups", "loadLookups",
                "restartTranIdSequence", "loadPendingAuthorizations");
        assertThat(count("pauth.auth_summary")).isEqualTo(21);
        assertThat(count("pauth.auth_detail")).isEqualTo(202);
        assertThat(DB.admin().sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single())
                .as("the first fixed-mode ID is the start of the reserved range").isEqualTo(9_000_000_000_000_000L);
    }

    /**
     * restartTranIdSequence takes the highest ID over every core.daily_tran row, whatever its
     * cycle. The row is a claimed one of a COMPLETED cycle, posted history that a reload keeps, so
     * the load reaches that step even where it refuses waiting online adds.
     */
    @Test
    void aClaimedRowAtTheEndOfTheReservedRangeFailsTheLoadWithTranIdExhausted() throws Exception {
        String completedDate = "2101-07-01";
        DB.fixtures().claimedDailyRow(completedDate, "DAILY_FILE", "9999999999999999", "4111111111111111");
        DB.admin().sql("UPDATE batch.cycle SET status = 'COMPLETED', closed_at = SYSUTCDATETIME()"
                + " WHERE business_date = :d").param("d", completedDate).update();
        try {
            JobExecution execution = run();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failures(execution)).contains("TRAN_ID_EXHAUSTED");
            assertThat(execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("restartTranIdSequence"))
                    .map(StepExecution::getStatus)).containsExactly(BatchStatus.FAILED);
        } finally {
            DB.admin().sql("DELETE d FROM core.daily_tran AS d JOIN batch.cycle AS c ON c.cycle_id = d.cycle_id"
                    + " WHERE c.business_date = :d").param("d", completedDate).update();
            DB.admin().sql("DELETE FROM batch.cycle WHERE business_date = :d").param("d", completedDate).update();
        }
    }

    @Test
    void theLedgerModeOfTheGroupIdSwitchIsFixed() {
        assertThat(switches.mode(BatchSwitches.DEF_ACUP_002)).isEqualTo(FidelityMode.FIXED);
    }

    @Test
    void reExportEqualsTheSourceFilesByteForByte() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        CoreRows rows = new CoreRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));

        assertSameRecords("acctdata.txt", Layouts.ACCOUNT.length(), rows.accounts(), a -> a.toBytes());
        assertSameRecords("custdata.txt", Layouts.CUSTOMER.length(), rows.customers(), c -> c.toBytes());
        // batch_svc reads the CVV masked, so only the admin login sees the bytes that were loaded.
        List<CardRecord> adminCards = new CoreRows(DB.admin()).cards();
        assertSameRecords("carddata.txt", Layouts.CARD.length(), adminCards, c -> c.toBytes());
        List<CardRecord> batchCards = rows.cards();
        assertThat(batchCards).extracting(CardRecord::cvvCd).as("batch_svc reads CARD-CVV-CD masked")
                .containsOnly("xxx");
        assertThat(batchCards.stream().map(DataLoadJobTest::withoutCvv).toList())
                .as("batch_svc reads every card field but CARD-CVV-CD as loaded")
                .isEqualTo(adminCards.stream().map(DataLoadJobTest::withoutCvv).toList());
        assertSameRecords("cardxref.txt", Layouts.CARD_XREF.length(), rows.cardXrefs(), x -> x.toBytes());
        assertSameRecords("tcatbal.txt", Layouts.TCAT_BAL.length(), rows.categoryBalances(), t -> t.toBytes());
        RefRows ref = new RefRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
        assertSameRecords("trantype.txt", Layouts.TRAN_TYPE.length(), ref.transactionTypes(), t -> t.toBytes());
        assertSameRecords("trancatg.txt", Layouts.TRAN_CATEGORY.length(), ref.categories(), c -> c.toBytes());
        assertSameRecords("discgrp.txt", Layouts.DISC_GROUP.length(), ref.disclosureGroups(), d -> d.toBytes());
    }

    @Test
    void theLookupTablesHoldTheListsOfTheValidators() throws Exception {
        assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        RefRows ref = new RefRows(JdbcClient.create(DB.dataSource(ServiceLogin.BATCH)));
        RuleTable rules = RuleTable.standard();

        assertThat(ref.states()).containsExactlyInAnyOrderElementsOf(rules.list("usStateCodes")).contains("PR");
        assertThat(ref.stateZipPrefixes()).containsExactlyInAnyOrderElementsOf(rules.list("usStateZipPrefixes"))
                .contains("AA34");
        assertThat(ref.areaCodes()).containsExactlyInAnyOrderElementsOf(rules.list("nanpGeneralPurposeAreaCodes"))
                .contains("201");
    }

    private static CardRecord withoutCvv(CardRecord c) {
        return new CardRecord(c.cardNum(), c.acctId(), null, c.embossedName(), c.expirationDate(), c.activeStatus(),
                c.legacyFiller());
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
        assertThatThrownBy(() -> batch.sql("SELECT TOP 1 tran_id FROM billpay.payment").query().listOfRows())
                .hasMessageContaining("permission was denied");
        assertThatThrownBy(() -> batch.sql("UPDATE ref.disclosure_group SET int_rate = 0").update())
                .as("the load replaces reference rows; maintenance is reference-service's")
                .hasMessageContaining("permission was denied");
        assertThat(batch.sql("SELECT COUNT(*) FROM batch.cycle").query(Integer.class).single()).isNotNegative();
    }

}
