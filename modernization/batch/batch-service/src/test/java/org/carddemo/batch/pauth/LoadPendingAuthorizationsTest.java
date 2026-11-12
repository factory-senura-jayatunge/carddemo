package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.carddemo.batch.load.DataLoadProperties;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.legacyformat.record.HdUnloadReader;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * loadPendingAuthorizations on the sample unload, after dataLoadJob has loaded the core sample.
 * The expected totals come from the legacy-format module's own decoder of the same file
 * ({@code platform/legacy-format/src/test/resources/golden/pauth-hd-unload.json}), not from this
 * loader. The step bean reads {@code carddemo.batch.pauth.source-file} as a path relative to the
 * working directory that starts with {@code ..}, as the default in application.properties does.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class LoadPendingAuthorizationsTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path REPO = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    static final Path SAMPLE_DIR = REPO.resolve("app/data/ASCII");
    static final Path UNLOAD = REPO.resolve(
            "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat");
    static final String UNLOAD_NAME = UNLOAD.getFileName().toString();
    static final String[] REF_TABLES = {"ref.disclosure_group", "ref.tran_category", "ref.tran_type",
            "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code"};

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient db;

    @Autowired
    Job dataLoadJob;

    @Autowired
    @Qualifier("pauthLoadStep")
    Step pauthLoadStep;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
        registry.add("carddemo.batch.pauth.source-file",
                () -> Path.of("").toAbsolutePath().relativize(UNLOAD).toString());
    }

    @BeforeEach
    void emptyTables() {
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
        for (String table : REF_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
    }

    private void loadCore() throws Exception {
        JobExecution core = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(core.getStatus()).as(failures(core)).isEqualTo(BatchStatus.COMPLETED);
        // dataLoadJob ends with pauthLoadStep; each test runs the step itself on empty pauth tables.
        DB.admin().sql("DELETE FROM pauth.auth_detail").update();
        DB.admin().sql("DELETE FROM pauth.auth_summary").update();
    }

    private JobExecution run(Step step) throws Exception {
        Job job = new JobBuilder("pauthLoadTestJob", jobRepository).start(step).build();
        return jobOperator.start(job,
                new JobParametersBuilder().addString("runKey", "test/" + UUID.randomUUID()).toJobParameters());
    }

    private Step step(String sourceFile, boolean reloadAllowed) {
        PendingAuthorizationProperties properties = new PendingAuthorizationProperties(sourceFile, 5L, 10L);
        return new StepBuilder(LoadPendingAuthorizationsTasklet.STEP_NAME, jobRepository)
                .tasklet(new LoadPendingAuthorizationsTasklet(db, properties,
                        new DataLoadProperties(SAMPLE_DIR.toString(), reloadAllowed)), transactionManager)
                .build();
    }

    private static String failures(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().map(Throwable::getMessage)
                .collect(Collectors.joining(" | "));
    }

    private static long count(String table) {
        return DB.admin().sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single();
    }

    private static void failedWithoutRows(JobExecution execution) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count("pauth.auth_summary")).as("summaries").isZero();
        assertThat(count("pauth.auth_detail")).as("details").isZero();
        // D-10: no account (11 digits) or card number (16 digits) in a failure.
        assertThat(failures(execution)).doesNotContainPattern("\\d{11}");
    }

    @Test
    void theStepBeanLoadsTheSampleUnloadAsTheIndependentDecoderReadsIt() throws Exception {
        loadCore();

        JobExecution execution = run(pauthLoadStep);

        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo("loadPendingAuthorizations");
        assertThat(step.getReadCount()).as("segments read, the blank root included").isEqualTo(224);
        assertThat(step.getWriteCount()).as("rows written").isEqualTo(223);

        Map<String, Object> s = DB.admin().sql("""
                SELECT COUNT_BIG(*) AS n, SUM(CAST(acct_id AS BIGINT)) AS acct_sum, SUM(credit_limit) AS credit_limit,
                    SUM(cash_limit) AS cash_limit, SUM(credit_balance) AS credit_balance,
                    SUM(cash_balance) AS cash_balance, SUM(approved_auth_cnt) AS approved_cnt,
                    SUM(approved_auth_amt) AS approved_amt, SUM(declined_auth_cnt) AS declined_cnt,
                    SUM(declined_auth_amt) AS declined_amt
                FROM pauth.auth_summary""").query().singleRow();
        assertThat(s.get("n")).isEqualTo(21L);
        assertThat(s.get("acct_sum")).isEqualTo(570L);
        assertThat((BigDecimal) s.get("credit_limit")).isEqualByComparingTo("85089.00");
        assertThat((BigDecimal) s.get("cash_limit")).isEqualByComparingTo("45585.00");
        assertThat((BigDecimal) s.get("credit_balance")).isEqualByComparingTo("1838.30");
        assertThat((BigDecimal) s.get("cash_balance")).isEqualByComparingTo("0.00");
        assertThat(s.get("approved_cnt")).isEqualTo(202);
        assertThat((BigDecimal) s.get("approved_amt")).isEqualByComparingTo("1838.30");
        assertThat(s.get("declined_cnt")).isEqualTo(0);
        assertThat((BigDecimal) s.get("declined_amt")).isEqualByComparingTo("0.00");

        Map<String, Object> d = DB.admin().sql("""
                SELECT COUNT_BIG(*) AS n, SUM(approved_amt) AS approved, SUM(transaction_amt) AS requested,
                    SUM(CAST(auth_date_9c AS BIGINT)) AS date9c, SUM(CAST(auth_time_9c AS BIGINT)) AS time9c,
                    COUNT(DISTINCT card_num) AS cards, MIN(CAST(processed_at AS DATE)) AS first_day,
                    MAX(CAST(processed_at AS DATE)) AS last_day,
                    COUNT(CASE WHEN auth_resp_code = '00' AND match_status = 'P' AND auth_fraud = ' '
                               AND fraud_rpt_date IS NULL THEN 1 END) AS plain
                FROM pauth.auth_detail""").query().singleRow();
        assertThat(d.get("n")).isEqualTo(202L);
        assertThat((BigDecimal) d.get("approved")).isEqualByComparingTo("1838.30");
        assertThat((BigDecimal) d.get("requested")).isEqualByComparingTo("1838.30");
        assertThat(d.get("date9c")).isEqualTo(15_488_834L);
        assertThat(d.get("time9c")).isEqualTo(181_537_233_054L);
        assertThat(d.get("cards")).isEqualTo(21);
        // Key dates YYDDD 23292 and 24096: 2023-10-19 and 2024-04-05.
        assertThat(((java.sql.Date) d.get("first_day")).toLocalDate()).isEqualTo(LocalDate.ofYearDay(2023, 292));
        assertThat(((java.sql.Date) d.get("last_day")).toLocalDate()).isEqualTo(LocalDate.ofYearDay(2024, 96));
        assertThat(d.get("plain")).isEqualTo(202);

        // processed_at and the stored complements describe the same instant.
        assertThat(DB.admin().sql("""
                SELECT COUNT(*) FROM pauth.auth_detail
                WHERE (99999 - auth_date_9c) / 1000 + 2000 <> YEAR(processed_at)
                   OR (99999 - auth_date_9c) % 1000 <> DATEPART(dayofyear, processed_at)""")
                .query(Integer.class).single()).isZero();
        // Each summary's approved count matches its own details (the decoder's check, here after the load).
        assertThat(DB.admin().sql("""
                SELECT COUNT(*) FROM pauth.auth_summary AS s
                WHERE s.approved_auth_cnt <> (SELECT COUNT(*) FROM pauth.auth_detail AS d WHERE d.acct_id = s.acct_id)
                   OR s.approved_auth_amt <> (SELECT SUM(d.approved_amt) FROM pauth.auth_detail AS d
                                              WHERE d.acct_id = s.acct_id)""")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aSecondLoadIsRefusedWhenNoReloadIsAllowedAndLeavesTheRows() throws Exception {
        loadCore();
        assertThat(run(pauthLoadStep).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution again = run(pauthLoadStep);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(again)).contains("already hold 21 and 202 rows", "D-41");
        assertThat(count("pauth.auth_summary")).isEqualTo(21);
        assertThat(count("pauth.auth_detail")).isEqualTo(202);
    }

    @Test
    void aReloadReplacesTheRowsWithoutReseedingTheDetailIdentity() throws Exception {
        loadCore();
        assertThat(run(pauthLoadStep).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long highest = DB.admin().sql("SELECT MAX(auth_id) FROM pauth.auth_detail").query(Long.class).single();

        JobExecution reload = run(step(UNLOAD.toString(), true));

        assertThat(reload.getStatus()).as(failures(reload)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("pauth.auth_summary")).isEqualTo(21);
        assertThat(count("pauth.auth_detail")).isEqualTo(202);
        assertThat(DB.admin().sql("SELECT MIN(auth_id) FROM pauth.auth_detail").query(Long.class).single())
                .isGreaterThan(highest);
    }

    @Test
    void aSummaryWhoseAccountIsNotLoadedFailsTheStepNamingTheRecordAndFieldOnly() throws Exception {
        JobExecution execution = run(step(UNLOAD.toString(), false));

        failedWithoutRows(execution);
        assertThat(failures(execution)).matches(UNLOAD_NAME.replace(".", "\\.")
                + " record \\d+: PA-ACCT-ID names an account that is not loaded");
    }

    @Test
    void aMissingOrUnsetSourceFileFailsTheStep() throws Exception {
        loadCore();

        JobExecution missing = run(step("no-such-dir/DBPAUTP0.dat", false));
        JobExecution unset = run(step(" ", false));

        failedWithoutRows(missing);
        assertThat(failures(missing)).isEqualTo("DBPAUTP0.dat not found (carddemo.batch.pauth.source-file)");
        failedWithoutRows(unset);
        assertThat(failures(unset)).contains("set carddemo.batch.pauth.source-file");
    }

    @Test
    void aTruncatedUnloadFailsTheStepNamingOnlyTheFile(@TempDir Path dir) throws Exception {
        loadCore();
        byte[] whole = Files.readAllBytes(UNLOAD);
        Path cut = dir.resolve(UNLOAD_NAME);
        Files.write(cut, Arrays.copyOf(whole, 10_000));

        JobExecution execution = run(step(cut.toString(), false));

        failedWithoutRows(execution);
        assertThat(failures(execution)).startsWith(UNLOAD_NAME + ": ");
    }

    private static long identity() {
        return DB.admin().sql("SELECT CAST(IDENT_CURRENT('pauth.auth_detail') AS BIGINT)").query(Long.class).single();
    }

    @Test
    void aResponseCodeOtherThan00Or05FailsTheStepBeforeAnyWriteBlankIncluded(@TempDir Path dir) throws Exception {
        loadCore();
        assertThat(run(pauthLoadStep).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long firstId = DB.admin().sql("SELECT MIN(auth_id) FROM pauth.auth_detail").query(Long.class).single();
        long identity = identity();
        PauthUnload.Patched blank = PauthUnload.withFirstDetailField("PA-AUTH-RESP-CODE", new byte[] {0x40, 0x40});

        JobExecution execution = run(step(PauthUnload.write(dir, blank).toString(), true));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(execution)).isEqualTo(UNLOAD_NAME + " record " + blank.recordNumber()
                + ": PA-AUTH-RESP-CODE is not one of the values the table accepts");
        assertThat(count("pauth.auth_summary")).isEqualTo(21);
        assertThat(count("pauth.auth_detail")).isEqualTo(202);
        assertThat(DB.admin().sql("SELECT MIN(auth_id) FROM pauth.auth_detail").query(Long.class).single())
                .as("the rows of the first load").isEqualTo(firstId);
        assertThat(identity()).as("no detail was inserted, not even in the rolled-back transaction").isEqualTo(identity);
    }

    /**
     * A loaded text value is stored unchanged or the step fails naming the column; SQL Server
     * would otherwise store {@code ?} for a character the column's code page lacks. Which of the
     * two happens depends on the column's type, which the authorization migrations own.
     */
    private void storedUnchangedOrRefusedByName(Path dir, String field, String column, byte[] bytes)
            throws Exception {
        loadCore();
        PauthUnload.Patched patched = PauthUnload.withFirstDetailField(field, bytes);
        PendingAuthorizationRows.Detail expected = PendingAuthorizationRows.detail(
                HdUnloadReader.read(patched.bytes(), UNLOAD_NAME, PendingAuthorizationRows.SEGMENTS).stream()
                        .filter(s -> s.recordNumber() == patched.recordNumber()).findFirst().orElseThrow().data());
        String value = (String) PendingAuthorizationRows.detailColumns("", expected).get(column);
        assertThat(value).as("the patched value is not ASCII").matches(".*[^\\x00-\\x7F].*");

        JobExecution execution = run(step(PauthUnload.write(dir, patched).toString(), false));

        if (execution.getStatus() == BatchStatus.COMPLETED) {
            // The key is unique per summary only, so another account may hold a row with the same key.
            List<String> stored = DB.admin().sql("SELECT CAST(" + column + " AS NVARCHAR(10)) FROM pauth.auth_detail"
                    + " WHERE auth_date_9c = :date AND auth_time_9c = :time")
                    .param("date", expected.date9c()).param("time", expected.time9c()).query(String.class).list();
            assertThat(stored).as(column + " stored unchanged").contains(value);
        } else {
            failedWithoutRows(execution);
            assertThat(failures(execution)).startsWith(UNLOAD_NAME + " record " + patched.recordNumber()
                    + ": pauth.auth_detail." + column + " cannot hold the value: ");
        }
    }

    @Test
    void anAuthResponseReasonIsStoredUnchangedOrRefusedByName(@TempDir Path dir) throws Exception {
        // IBM037 X'20' is U+0080, which code page 1252 lacks.
        storedUnchangedOrRefusedByName(dir, "PA-AUTH-RESP-REASON", "auth_resp_reason",
                new byte[] {0x20, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0});
    }

    @Test
    void anAuthIdCodeIsStoredUnchangedOrRefusedByName(@TempDir Path dir) throws Exception {
        // IBM037 X'51' is U+00E9, two bytes in UTF-8: six characters, seven bytes.
        storedUnchangedOrRefusedByName(dir, "PA-AUTH-ID-CODE", "auth_id_code",
                new byte[] {0x51, (byte) 0xC1, (byte) 0xC2, (byte) 0xF1, (byte) 0xF2, (byte) 0xC3});
    }

    @Test
    void aRowTheDatabaseRefusesRollsBackTheStepAndGivesOnlyTheErrorNumber() throws Exception {
        loadCore();
        DB.admin().sql("ALTER TABLE pauth.auth_detail WITH NOCHECK ADD CONSTRAINT CK_test_refuse_load "
                + "CHECK (auth_type = 'NONE')").update();
        JobExecution execution;
        try {
            execution = run(step(UNLOAD.toString(), false));
        } finally {
            DB.admin().sql("ALTER TABLE pauth.auth_detail DROP CONSTRAINT CK_test_refuse_load").update();
        }

        failedWithoutRows(execution);
        assertThat(failures(execution)).matches(UNLOAD_NAME.replace(".", "\\.")
                + " record \\d+: pauth.auth_detail refused the row \\(SQL Server error 547\\)");
    }
}
