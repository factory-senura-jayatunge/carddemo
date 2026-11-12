package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.ledger.BalancePosting;
import org.carddemo.platform.ledger.TransactionWriter;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * calculateInterestJob's rules on hand-made accounts: truncation, rates of zero, the DEFAULT
 * fallback, negative balances (DEF-INTC-012), the cycle reset, missing reference data, and the
 * end of the ID range in both modes of DEF-INTC-010.
 *
 * <p>Account 1 (blank group, so DEFAULT rates) has a positive, two negative, a zero and a
 * zero-rate balance; account 2 (group GOLD, two cards) has a GOLD rate and a DEFAULT fallback;
 * account 3 has only a zero-rate balance; account 4 has no category balance.
 */
@BothModes({"DEF-JINTC-001", "DEF-INTC-010", "DEF-INTC-012"})
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7",
        "carddemo.batch.interest.legacy-parm=2022071800"})
class InterestRulesTest extends CycleJobTestBase {

    private static final String DATE = "2101-06-05";
    private static final String CUSTOMER = "000000001";
    private static final String START = "1000.00 50.00 20.00";

    @Autowired
    JdbcClient db;

    @Autowired
    TransactionWriter transactions;

    @Autowired
    BalancePosting balances;

    @Autowired
    FidelitySwitches switches;

    @Autowired
    InterestProperties interest;

    @Autowired
    PlatformTransactionManager tx;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    LeaseKeeper keeper;

    private long sequenceStart;

    private static String card(int n) {
        return "9".repeat(12) + String.format("%04d", n);
    }

    private static String acct(int n) {
        return String.format("%011d", n);
    }

    @BeforeEach
    void accounts() {
        DB.admin().sql("DELETE FROM ref.disclosure_group").update();
        rate("DEFAULT", "01", "0001", "18.00");
        rate("DEFAULT", "01", "0002", "18.00");
        rate("DEFAULT", "01", "0003", "15.00");
        rate("DEFAULT", "01", "0004", "18.00");
        rate("DEFAULT", "02", "0001", "0.00");
        rate("GOLD", "01", "0001", "24.00");
        DB.fixtures().customer(CUSTOMER);
        for (int n = 1; n <= 4; n++) {
            DB.fixtures().account(CoreFixtures.Account.of(acct(n), "1000.00").withCycleTotals("50.00", "20.00"));
        }
        DB.admin().sql("UPDATE core.account SET group_id = 'GOLD' WHERE acct_id = :a").param("a", acct(2)).update();
        DB.fixtures().card(card(1), acct(1), CUSTOMER).card(card(6), acct(2), CUSTOMER).card(card(2), acct(2), CUSTOMER)
                .card(card(3), acct(3), CUSTOMER).card(card(4), acct(4), CUSTOMER);
        DB.fixtures().categoryBalance(acct(1), "01", "0001", "333.33")
                .categoryBalance(acct(1), "01", "0002", "-10.01")
                .categoryBalance(acct(1), "01", "0003", "-0.50")
                .categoryBalance(acct(1), "01", "0004", "0.00")
                .categoryBalance(acct(1), "02", "0001", "500.00")
                // 24.0098 and 1.509: truncated one by one they add up to 25.50, the sum truncated once is 25.51.
                .categoryBalance(acct(2), "01", "0001", "1200.49")
                .categoryBalance(acct(2), "01", "0002", "100.60")
                .categoryBalance(acct(3), "02", "0001", "-40.00");
        sequenceStart = DB.fixtures().tranIdSequenceMinimum() + 700;
        DB.fixtures().restartTranIdSequence(sequenceStart);
    }

    private static void rate(String group, String type, String cat, String rate) {
        DB.admin().sql("INSERT INTO ref.disclosure_group (group_id, type_cd, cat_cd, int_rate) VALUES (:g, :t, :c, :r)")
                .param("g", group).param("t", type).param("c", cat).param("r", new BigDecimal(rate)).update();
    }

    private static boolean legacy() {
        return FidelityOverrides.current() == FidelityMode.LEGACY;
    }

    private String id(int n) {
        return legacy() ? "2022071800" + String.format("%06d", n) : String.format("%016d", sequenceStart + n - 1);
    }

    /** "tran_id account amount card" of each interest transaction of the cycle, in ID order; cards by number. */
    private static List<String> interestRows(long cycle) {
        return DB.admin().sql("""
                SELECT CONCAT(tran_id, ' ', RIGHT(description, 11) COLLATE Latin1_General_100_BIN2, ' ', amount, ' card',
                    RIGHT(card_num, 1))
                FROM core.[transaction] WHERE cycle_id = :c AND origin = 'INTEREST' ORDER BY tran_id""")
                .param("c", cycle).query(String.class).list();
    }

    private static List<String> accountStates() {
        return DB.admin().sql("SELECT CONCAT(acct_id, ' ', curr_bal, ' ', curr_cyc_credit, ' ', curr_cyc_debit)"
                + " FROM core.account ORDER BY acct_id").query(String.class).list();
    }

    private static List<String> untouched() {
        return IntStream.rangeClosed(1, 4).mapToObj(n -> acct(n) + " " + START).toList();
    }

    @Test
    @SpecItem({"BR-INTC-001", "BR-INTC-002", "BR-INTC-003", "BR-INTC-004", "BR-INTC-006", "BR-INTC-007",
            "NUM-INTC-002", "DEF-INTC-012", "DEF-INTC-010", "INV-001"})
    void eachRuleOnItsOwnBalance() {
        long cycle = openCycle(DATE, "always");

        JobExecution run = launch("calculateInterestJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        if (legacy()) {
            // 333.33 x 18 / 1200 = 4.99995; -10.01 x 18 / 1200 = -0.15015; -0.50 x 15 / 1200 = -0.00625.
            assertThat(interestRows(cycle)).containsExactly(
                    id(1) + " " + acct(1) + " 4.99 card1",
                    id(2) + " " + acct(1) + " -0.15 card1",
                    id(3) + " " + acct(1) + " 0.00 card1",
                    id(4) + " " + acct(1) + " 0.00 card1",
                    id(5) + " " + acct(2) + " 24.00 card2",
                    id(6) + " " + acct(2) + " 1.50 card2");
            assertThat(accountStates()).containsExactly(acct(1) + " 1004.84 0.00 0.00", acct(2) + " 1025.50 0.00 0.00",
                    acct(3) + " 1000.00 0.00 0.00", acct(4) + " " + START);
        } else {
            assertThat(interestRows(cycle)).containsExactly(
                    id(1) + " " + acct(1) + " 4.99 card1",
                    id(2) + " " + acct(1) + " 0.00 card1",
                    id(3) + " " + acct(2) + " 24.00 card2",
                    id(4) + " " + acct(2) + " 1.50 card2");
            assertThat(accountStates()).containsExactly(acct(1) + " 1004.99 0.00 0.00", acct(2) + " 1025.50 0.00 0.00",
                    acct(3) + " 1000.00 0.00 0.00", acct(4) + " " + START);
        }
        StepExecution step = IngestDailyFileJobTest.step(run, "interest");
        assertThat(step.getReadCount()).isEqualTo(8);
        assertThat(step.getWriteCount()).isEqualTo(3);
    }

    @Test
    @SpecItem({"BR-INTC-002", "ERR-INTC-005"})
    void aRateMissingFromTheGroupAndFromDefaultFailsWithNothingWritten() {
        DB.admin().sql("DELETE FROM ref.disclosure_group WHERE group_id = 'DEFAULT' AND type_cd = '01' AND cat_cd = '0002'")
                .update();
        long cycle = openCycle(DATE, "always");

        JobExecution run = launch("calculateInterestJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).isEqualTo("DISCLOSURE_GROUP_MISSING: no rate for type 01"
                + " category 0002 in the account's group or in DEFAULT");
        assertThat(interestRows(cycle)).isEmpty();
        assertThat(accountStates()).containsExactlyElementsOf(untouched());
    }

    @Test
    @SpecItem({"ERR-INTC-004", "DEF-INTC-009"})
    void anAccountWithoutACardFailsAndRollsBackTheAccountsOfItsChunk() {
        DB.admin().sql("DELETE FROM core.card_xref WHERE acct_id = :a").param("a", acct(3)).update();
        long cycle = openCycle(DATE, "always");

        JobExecution run = launch("calculateInterestJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).isEqualTo("CARD_XREF_NOT_FOUND: account 3 of the"
                + " interest run in category balance order has no card cross-reference");
        assertThat(interestRows(cycle)).isEmpty();
        assertThat(accountStates()).containsExactlyElementsOf(untouched());
    }

    /**
     * The tasklet as the step runs one chunk, in a transaction, with the execution context a
     * restart would bring: legacy mode continues the counter from {@code lastSuffix}. The chunk
     * runs as the step does, under the fencing transaction manager, for a step of a job execution
     * created through the guarded repository, which holds its lease; the execution ends after the
     * chunk, which releases the lease.
     */
    private void runChunk(long cycle, int lastSuffix) {
        JobParameters params = new JobParametersBuilder()
                .addLong("cycleId", cycle).addLocalDate("businessDate", LocalDate.parse(DATE))
                .addLong("attempt", 1L).addString("interestParm", "2101060500")
                .addString("chunkProbe", UUID.randomUUID().toString()).toJobParameters();
        JobInstance instance = jobRepository.createJobInstance("interestChunkProbe", params);
        JobExecution execution = jobRepository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStartTime(LocalDateTime.now());
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);
        assertThat(keeper.owns(execution.getId())).as("lease of the chunk's job execution").isTrue();
        StepExecution step = jobRepository.createStepExecution("interest", execution);
        step.getExecutionContext().putInt(CalculateInterestTasklet.LAST_SUFFIX, lastSuffix);
        CalculateInterestTasklet tasklet = new CalculateInterestTasklet(db, transactions, balances, switches, interest,
                Clock.systemDefaultZone(), 7);
        BatchStatus end = BatchStatus.FAILED;
        StepSynchronizationManager.register(step);
        try {
            new TransactionTemplate(tx).executeWithoutResult(
                    s -> tasklet.execute(step.createStepContribution(), new ChunkContext(new StepContext(step))));
            end = BatchStatus.COMPLETED;
        } finally {
            StepSynchronizationManager.close();
            execution.setStatus(end);
            execution.setExitStatus(end == BatchStatus.COMPLETED ? ExitStatus.COMPLETED : ExitStatus.FAILED);
            execution.setEndTime(LocalDateTime.now());
            jobRepository.update(execution);
        }
    }

    private static long sequenceMaximum() {
        return DB.admin().sql("SELECT CAST(maximum_value AS BIGINT) FROM sys.sequences"
                + " WHERE object_id = OBJECT_ID('core.tran_id_seq')").query(Long.class).single();
    }

    /** Positions the ID source so that {@code left} IDs remain; returns the last ID of the range. */
    private String leaveIds(int left, int[] lastSuffix) {
        if (legacy()) {
            lastSuffix[0] = CalculateInterestTasklet.MAX_SUFFIX - left;
            return "2022071800" + CalculateInterestTasklet.MAX_SUFFIX;
        }
        long max = sequenceMaximum();
        DB.fixtures().restartTranIdSequence(max - left + 1);
        return String.format("%016d", max);
    }

    private int transactionsOfTheRun() {
        return legacy() ? 6 : 4;
    }

    @Test
    @SpecItem({"DEF-INTC-010", "NUM-JINTC-001", "INV-001"})
    void theLastIdOfTheRangeIsUsed() {
        long cycle = openCycle(DATE, "always");
        int[] lastSuffix = {0};
        String last = leaveIds(transactionsOfTheRun(), lastSuffix);

        runChunk(cycle, lastSuffix[0]);

        List<String> rows = interestRows(cycle);
        assertThat(rows).hasSize(transactionsOfTheRun());
        assertThat(rows.get(rows.size() - 1)).startsWith(last + " " + acct(2) + " 1.50");
    }

    @Test
    @SpecItem({"DEF-INTC-010", "NUM-JINTC-001", "INV-001"})
    void noIdWrapsAndTheChunkRollsBack() {
        long cycle = openCycle(DATE, "always");
        int[] lastSuffix = {0};
        leaveIds(transactionsOfTheRun() - 1, lastSuffix);

        assertThatThrownBy(() -> runChunk(cycle, lastSuffix[0])).isInstanceOfSatisfying(CycleJobException.class,
                e -> assertThat(e.code()).isEqualTo("TRAN_ID_EXHAUSTED"));

        assertThat(interestRows(cycle)).isEmpty();
        assertThat(accountStates()).containsExactlyElementsOf(untouched());
    }
}
