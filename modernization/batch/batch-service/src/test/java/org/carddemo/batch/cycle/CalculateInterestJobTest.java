package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.carddemo.batch.internal.InternalApiException;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * calculateInterestJob (CBACT04C) after the sample day's posting, against GnuCOBOL's INTCALC run
 * on the files the sample POSTTRAN leaves, with PARM {@code 2022071800}
 * ({@code docs/modernization/spec/checks/intcalc_reimpl}): one interest transaction per account
 * (its type 01 balance; the type 03 balances have rate 0) and the 50 balances after. The sample
 * has no negative balance with a non-zero rate, so the amounts are the same in both modes of
 * DEF-INTC-012; the IDs are the PARM and a counter in legacy mode and reserved IDs from
 * {@code core.tran_id_seq} in fixed mode (DEF-INTC-010).
 */
@BothModes({"DEF-JINTC-001", "DEF-INTC-010", "DEF-INTC-012"})
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7",
        "carddemo.batch.interest.legacy-parm=2022071800"})
class CalculateInterestJobTest extends CycleJobTestBase {

    static final String DATE = "2101-06-04";
    private static final List<String> REFERENCE_TABLES = List.of("ref.disclosure_group", "ref.tran_category",
            "ref.tran_type", "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    /** GnuCOBOL's interest transaction amounts, for accounts 00000000001 to 00000000050 in order. */
    static final List<String> INTEREST = List.of(
            "14.56", "29.24", "32.38", "24.32", "15.83", "34.38", "16.08", "29.60", "36.34", "19.11",
            "25.90", "29.46", "35.94", "27.14", "51.02", "16.83", "4.29", "33.27", "32.98", "30.66",
            "11.10", "41.61", "23.51", "27.04", "19.87", "18.99", "44.80", "10.11", "39.63", "0.36",
            "8.30", "14.66", "42.37", "32.17", "16.74", "28.87", "0.00", "37.34", "25.63", "20.43",
            "29.99", "27.05", "36.72", "25.15", "30.09", "44.71", "19.97", "18.90", "24.95", "18.77");

    /** GnuCOBOL's ACCT-CURR-BAL after INTCALC, for accounts 00000000001 to 00000000050 in order. */
    static final List<String> BALANCE_AFTER = List.of(
            "1302.66", "1764.21", "1890.03", "1686.98", "1198.69", "2606.57", "916.29", "2167.68", "3243.89",
            "1169.87", "1565.89", "1800.11", "2896.04", "1503.13", "4263.56", "1825.71", "-617.27", "2725.92",
            "3080.51", "1933.76", "969.98", "2479.81", "1173.49", "1949.14", "763.63", "1212.86", "3540.45",
            "811.89", "2697.27", "-898.53", "341.40", "475.22", "3396.80", "2401.49", "1021.95", "2009.30",
            "-125.88", "3098.87", "2848.51", "1672.53", "2047.86", "1992.36", "3341.91", "2085.02", "2278.86",
            "3583.12", "1064.63", "1221.79", "1158.26", "1964.64");

    /** Where the test restarts core.tran_id_seq, above the start of the reserved range. */
    private static final long SEQUENCE_OFFSET = 500;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    private long sequenceStart;

    @BeforeEach
    void loadSample() {
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        for (String table : REFERENCE_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
        JobExecution load;
        try {
            load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                    .addString("runKey", "test/" + UUID.randomUUID())
                    .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /** The sample day ingested and posted in a cycle with interest due, core.tran_id_seq at a known value. */
    private long postedCycle() {
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE, "always");
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        sequenceStart = DB.fixtures().tranIdSequenceMinimum() + SEQUENCE_OFFSET;
        DB.fixtures().restartTranIdSequence(sequenceStart);
        return cycle;
    }

    private static boolean legacy() {
        return FidelityOverrides.current() == FidelityMode.LEGACY;
    }

    private static String acct(int n) {
        return String.format("%011d", n);
    }

    /** The ID of the {@code n}th interest transaction of an uninterrupted run. */
    private String id(int n) {
        return legacy() ? "2022071800" + String.format("%06d", n) : String.format("%016d", sequenceStart + n - 1);
    }

    /** "tran_id account amount" of each interest transaction of the cycle, in ID order. */
    private static List<String> interestRows(long cycle) {
        return DB.admin().sql("""
                SELECT CONCAT(tran_id, ' ', RIGHT(description, 11) COLLATE Latin1_General_100_BIN2, ' ', amount)
                FROM core.[transaction]
                WHERE cycle_id = :c AND origin = 'INTEREST' ORDER BY tran_id""").param("c", cycle)
                .query(String.class).list();
    }

    private static List<String> expectedRows(int accounts, IntFunction<String> idOf) {
        return IntStream.rangeClosed(1, accounts).mapToObj(n -> idOf.apply(n) + " " + acct(n) + " " + INTEREST.get(n - 1))
                .toList();
    }

    /** "account curr_bal curr_cyc_credit curr_cyc_debit" of every account. */
    private static List<String> accounts() {
        return DB.admin().sql("SELECT CONCAT(acct_id, ' ', curr_bal, ' ', curr_cyc_credit, ' ', curr_cyc_debit)"
                + " FROM core.account ORDER BY acct_id").query(String.class).list();
    }

    /** The accounts as GnuCOBOL leaves them for the first {@code charged}, as before for the others. */
    private static List<String> accountsAfter(List<String> before, int charged) {
        List<String> expected = new ArrayList<>(before);
        for (int n = 1; n <= charged; n++) {
            expected.set(n - 1, acct(n) + " " + BALANCE_AFTER.get(n - 1) + " 0.00 0.00");
        }
        return expected;
    }

    private static List<String> categoryBalances() {
        return DB.admin().sql("SELECT CONCAT(acct_id, ' ', type_cd, cat_cd, ' ', balance) FROM core.tcat_bal"
                + " ORDER BY acct_id, type_cd, cat_cd").query(String.class).list();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @SpecItem({"BR-INTC-001", "BR-INTC-002", "BR-INTC-003", "BR-INTC-004", "BR-INTC-005", "BR-INTC-006",
            "BR-INTC-007", "BR-INTC-008", "NUM-INTC-002", "NUM-INTC-006", "DEF-INTC-004", "DEF-INTC-010",
            "NUM-JINTC-001", "DEF-JINTC-001", "DEF-INTC-012", "DEF-INTC-011", "INV-001"})
    void chargesTheSampleInterestAsGnuCobolDoes(CapturedOutput output) {
        long cycle = postedCycle();
        List<String> before = accounts();
        List<String> categories = categoryBalances();
        assertThat(before).hasSize(50);
        assertThat(categories).hasSize(100);

        JobExecution run = launch("calculateInterestJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(interestRows(cycle)).containsExactlyElementsOf(expectedRows(50, this::id));
        assertThat(accounts()).containsExactlyElementsOf(accountsAfter(before, 50));
        assertThat(categoryBalances()).as("category balances are not changed").containsExactlyElementsOf(categories);
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM core.[transaction] AS t
                WHERE t.cycle_id = ? AND t.origin = 'INTEREST' AND NOT (t.type_cd = '01' AND t.cat_cd = '0005'
                    AND t.source = 'System' AND t.description = CONCAT('Int. for a/c ', RIGHT(t.description, 11))
                    AND t.merchant_id = '000000000' AND t.merchant_name = '' AND t.merchant_city = ''
                    AND t.merchant_zip = '' AND t.in_master = 1 AND t.orig_ts = t.proc_ts
                    AND CAST(t.proc_ts AS DATE) = ?
                    AND t.card_num = (SELECT MIN(x.card_num) FROM core.card_xref AS x
                        WHERE x.acct_id = RIGHT(t.description, 11) COLLATE Latin1_General_100_BIN2))""", cycle, DATE)).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE cycle_id = ?", cycle))
                .as("the posted transactions and the interest, no fees").isEqualTo(262 + 50);
        StepExecution step = IngestDailyFileJobTest.step(run, "interest");
        assertThat(step.getReadCount()).isEqualTo(100);
        assertThat(step.getWriteCount()).isEqualTo(50);
        assertThat(step.getExecutionContext().getLong(CalculateInterestTasklet.TRANSACTIONS)).isEqualTo(50);

        // DEF-INTC-011: counts only, no record.
        assertThat(output.getAll()).contains("interest: cycle " + cycle
                + " attempt 1 updated 50 accounts and wrote 50 interest transactions").doesNotContain("Int. for a/c");
        for (String card : DB.admin().sql("SELECT card_num FROM core.card_xref").query(String.class).list()) {
            assertThat(output.getAll()).as("the log holds a card number").doesNotContain(card);
        }
    }

    @Test
    @SpecItem({"DEF-INTC-009", "ERR-JINTC-001", "DEF-INTC-005", "DEF-INTC-010", "NUM-JINTC-001", "INV-001"})
    void aRestartResumesAtTheNextAccountAndASecondRunIsRefused() {
        long cycle = postedCycle();
        List<String> before = accounts();
        // Account 17 is the third of the third chunk of 7.
        Map<String, Object> xref = DB.admin().sql("SELECT card_num, cust_id FROM core.card_xref WHERE acct_id = :a")
                .param("a", acct(17)).query().singleRow();
        DB.admin().sql("DELETE FROM core.card_xref WHERE acct_id = :a").param("a", acct(17)).update();

        JobExecution failed = launch("calculateInterestJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).isEqualTo("CARD_XREF_NOT_FOUND: account 17 of the"
                + " interest run in category balance order has no card cross-reference");
        assertThat(interestRows(cycle)).as("the two committed chunks").containsExactlyElementsOf(expectedRows(14, this::id));
        assertThat(accounts()).containsExactlyElementsOf(accountsAfter(before, 14));
        DB.admin().sql("INSERT INTO core.card_xref (card_num, cust_id, acct_id) VALUES (:card, :cust, :a)")
                .param("card", xref.get("card_num")).param("cust", xref.get("cust_id")).param("a", acct(17)).update();

        JobExecution restarted = launch("calculateInterestJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(IngestDailyFileJobTest.step(restarted, "interest").getReadCount())
                .isEqualTo(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE acct_id > ?", acct(14)));
        // Fixed mode: the rolled-back chunk drew two sequence values (accounts 15 and 16), which stay unused.
        IntFunction<String> idAfterRestart = n -> legacy() || n <= 14 ? id(n) : id(n + 2);
        assertThat(interestRows(cycle)).containsExactlyElementsOf(expectedRows(50, idAfterRestart));
        assertThat(accounts()).containsExactlyElementsOf(accountsAfter(before, 50));

        // D-BAT-10: the completed instance is the cycle's interest; it cannot be charged again.
        assertThatThrownBy(() -> launch("calculateInterestJob", cycle)).isInstanceOf(InternalApiException.class)
                .hasMessageStartingWith("JOB_INSTANCE_COMPLETE");
        assertThat(interestRows(cycle)).hasSize(50);
        assertThat(accounts()).containsExactlyElementsOf(accountsAfter(before, 50));
    }

    @Test
    @SpecItem({"DEF-INTC-010", "INV-001", "DEF-INTC-009"})
    void anIdAlreadyInTheMasterFailsTheChunkAndRollsBackItsAccounts() {
        long cycle = postedCycle();
        List<String> before = accounts();
        String taken = id(17);
        String card = DB.admin().sql("SELECT MIN(card_num) FROM core.card_xref WHERE acct_id = :a").param("a", acct(17))
                .query(String.class).single();
        DB.fixtures().transaction(taken, card, "1.00");

        JobExecution failed = launch("calculateInterestJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).isEqualTo("DUPLICATE_TRAN_ID: interest tran_id "
                + taken + " of account 17 of the interest run is already in the master");
        assertThat(interestRows(cycle)).as("accounts 15 and 16 rolled back with 17")
                .containsExactlyElementsOf(expectedRows(14, this::id));
        assertThat(accounts()).containsExactlyElementsOf(accountsAfter(before, 14));
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE tran_id = ?", taken)).isEqualTo(1);
    }
}
