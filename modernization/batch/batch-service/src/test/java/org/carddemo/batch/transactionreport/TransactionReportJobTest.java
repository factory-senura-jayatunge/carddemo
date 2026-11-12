package org.carddemo.batch.transactionreport;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.HEADER;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.RULE;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.accountTotal;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.grandTotal;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.line;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.pageTotal;
import static org.carddemo.batch.transactionreport.TransactionReportWriterTest.signed;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.InternalApiException;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures.Account;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The job through its real entry point against SQL Server, compiled offline; running it needs a
 * scheduled database slot. Expected lines come from the section 4.2 column table, as in
 * {@link TransactionReportWriterTest}.
 */
@BothModes({"DEF-TRPT-001", "DEF-TRPT-004"})
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class TransactionReportJobTest {
    static final TestDatabase DB = TestDatabase.shared();
    static final String CARD_1 = "4000000000000001";
    static final String CARD_2 = "4000000000000002";
    static final String CARD_3 = "4000000000000003";
    static final List<String> CARDS = List.of(CARD_1, CARD_2, CARD_3);

    @Autowired JobLaunches launches;
    @Autowired JobRepository repository;
    @Autowired JobOperator jobOperator;
    @Autowired Job transactionReportJob;
    @Autowired OutputRoots roots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void data() {
        fixtures();
    }

    static void fixtures() {
        DB.fixtures().deleteAll().closeBatchWindows();
        DB.admin().sql("DELETE FROM batch.report_run WHERE requested_by = 'RPTTEST1'").update();
        DB.admin().sql("DELETE FROM ref.tran_category WHERE type_cd IN ('96', '97')").update();
        DB.admin().sql("DELETE FROM ref.tran_type WHERE type_cd IN ('96', '97')").update();
        DB.admin().sql("INSERT INTO ref.tran_type (type_cd, description) VALUES ('97', 'Report Test Type Description')")
                .update();
        DB.admin().sql("""
                INSERT INTO ref.tran_category (type_cd, cat_cd, description)
                VALUES ('97', '0097', 'Report Test Category Description Long')""").update();
        DB.fixtures().account(Account.of("00000000001", "0.00")).account(Account.of("00000000002", "0.00"))
                .customer("000000001").card(CARD_1, "00000000001", "000000001")
                .card(CARD_2, "00000000002", "000000001").card(CARD_3, "00000000001", "000000001");
    }

    static void transaction(String tranId, String card, String type, String procDate, String amount, int inMaster) {
        DB.admin().sql("""
                INSERT INTO core.[transaction] (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, origin, in_master)
                VALUES (:id, :type, '0097', 'POS TERM', 'REPORT FIXTURE', :amount, '000000001', 'FIXTURE',
                    'TOWN', '10001', :card, :ts, :ts, 'LOADED', :master)""")
                .param("id", tranId).param("type", type).param("amount", new BigDecimal(amount))
                .param("card", card).param("ts", procDate + " 10:00:00").param("master", inMaster).update();
    }

    static long requested() {
        return DB.admin().sql("""
                INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)
                OUTPUT inserted.report_run_id
                VALUES ('CUSTOM', '2022-07-01', '2022-07-06', 'RPTTEST1')""").query(Long.class).single();
    }

    static Map<String, Object> runRow(long runId) {
        return DB.admin().sql("""
                SELECT status, job_execution_id, failure_code, started_at, finished_at,
                       LOWER(CONVERT(CHAR(64), output_sha256, 2)) AS output_sha256
                FROM batch.report_run WHERE report_run_id = :id""").param("id", runId).query().singleRow();
    }

    /** The lower-case hex SHA-256 of a file's bytes, computed apart from the publication. */
    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    JobExecution launch(long runId) {
        var execution = launches.launch(TransactionReportConfiguration.JOB,
                Map.of("params", Map.of("runId", runId))).execution();
        return repository.getJobExecution(execution.getId());
    }

    /** Derived from the database marker, independently of the execution's bound root. */
    Path report(long runId) throws Exception {
        try (var connection = DB.adminDataSource().getConnection()) {
            return OutputRoot.root(roots.varDir().toRealPath(), OutputRoot.read(connection))
                    .resolve(ReportPublication.relativePath(runId));
        }
    }

    static String detail(String tranId, String acct, String shown) {
        return line(tranId + " " + acct + " 97-Report Test Typ 0097-Report Test Category Descript POS TERM  "
                + "    " + " ".repeat(15 - shown.length()) + shown);
    }

    static List<String> lines(Path file) throws Exception {
        String text = Files.readString(file, ISO_8859_1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 134) {
            assertThat(text.charAt(i + 133)).isEqualTo('\n');
            lines.add(text.substring(i, i + 133));
        }
        return lines;
    }

    static boolean fixed() {
        return FidelityOverrides.current() == FidelityMode.FIXED;
    }

    @Test
    @SpecItem({"BR-TRPT-002", "BR-TRPT-003", "BR-TRPT-004", "BR-TRPT-006", "BR-TRPT-007", "DEF-TRPT-001",
        "DEF-TRPT-004", "FLOW-TRPT-001", "SE-TRPT-001"})
    void reportsTheMasterInTheRunsRangeAndCompletesTheRunWithoutChangingData() throws Exception {
        transaction("0000000000000001", CARD_1, "97", "2022-07-01", "10.00", 1);
        transaction("0000000000000002", CARD_2, "97", "2022-07-02", "20.00", 1);
        transaction("0000000000000003", CARD_3, "97", "2022-07-06", "30.00", 1);
        transaction("0000000000000004", CARD_1, "97", "2022-06-30", "99.00", 1);
        transaction("0000000000000005", CARD_1, "97", "2022-07-07", "99.00", 1);
        transaction("0000000000000006", CARD_1, "97", "2022-07-02", "99.00", 0);
        var before = DB.fixtures().rowVersions("core.[transaction]");
        long runId = requested();

        JobExecution job = launch(runId);

        assertThat(job.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(runId)).containsEntry("status", "COMPLETED").containsEntry("job_execution_id", job.getId())
                .containsEntry("failure_code", null).containsEntry("output_sha256", sha256(report(runId)));
        List<String> body = fixed()
                ? List.of(detail("0000000000000001", "00000000001", "10.00"),
                        detail("0000000000000003", "00000000001", "30.00"), accountTotal(signed('+', "40.00")), RULE,
                        detail("0000000000000002", "00000000002", "20.00"), accountTotal(signed('+', "20.00")), RULE)
                : List.of(detail("0000000000000001", "00000000001", "10.00"), accountTotal(signed('+', "10.00")),
                        RULE, detail("0000000000000002", "00000000002", "20.00"),
                        accountTotal(signed('+', "20.00")), RULE, detail("0000000000000003", "00000000001", "30.00"),
                        accountTotal(signed('+', "30.00")), RULE);
        List<String> expected = new ArrayList<>(HEADER.stream()
                .map(text -> text.replace("2022-01-01 to 2022-07-06", "2022-07-01 to 2022-07-06")).toList());
        expected.addAll(body);
        expected.addAll(List.of(pageTotal(signed('+', "60.00")), RULE, grandTotal(signed('+', "60.00"))));
        assertThat(lines(report(runId))).containsExactlyElementsOf(expected);
        assertThat(job.getExecutionContext().getString("carddemo.output.report"))
                .isEqualTo("reports/tranrept/" + runId + "/TRANREPT.txt");
        assertThat(DB.fixtures().rowVersions("core.[transaction]")).isEqualTo(before);
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @SpecItem({"DEF-TRPT-001", "FLOW-TRPT-001", "BR-TRPT-006"})
    void anEmptySelectionCompletes() throws Exception {
        long runId = requested();
        JobExecution job = launch(runId);
        assertThat(job.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRow(runId)).containsEntry("output_sha256", sha256(report(runId)));
        List<String> lines = lines(report(runId));
        if (fixed()) {
            assertThat(lines).containsExactly(pageTotal(" ".repeat(15)), RULE, grandTotal(" ".repeat(15)));
        } else {
            assertThat(lines).isEmpty();
        }
    }

    @Test
    void aRunThatFailedBeforeItsTriggerIsNeverStarted() throws Exception {
        long runId = requested();
        DB.admin().sql("EXEC batch.report_run_trigger_failed @report_run_id = :id").param("id", runId).query()
                .listOfRows();
        // Past the launch check, as a launch racing the trigger failure would be.
        JobExecution job = jobOperator.start(transactionReportJob,
                new JobParametersBuilder().addLong("runId", runId, true).toJobParameters());
        assertThat(job.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(job.getExitStatus().getExitDescription()).isEqualTo("REPORT_RUN_NOT_RUNNABLE: report run " + runId);
        assertThat(runRow(runId)).containsEntry("status", "FAILED").containsEntry("failure_code", "TRIGGER_FAILED")
                .containsEntry("job_execution_id", null).containsEntry("output_sha256", null);
        assertThat(report(runId).getParent()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOB_FAILED", "OWNER_LOST", "JOB_STOPPED"})
    @SpecItem({"BR-TRPT-007", "ERR-TRPT-004", "DEF-TRPT-006"})
    void aMissingLookupFailsTheRunWithoutValuesAndARestartCompletesIt(String failureCode, CapturedOutput output)
            throws Exception {
        transaction("0000000000000001", CARD_1, "97", "2022-07-01", "10.00", 1);
        transaction("0000000000000002", CARD_2, "96", "2022-07-02", "20.00", 1);
        long runId = requested();

        JobExecution failed = launch(runId);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription())
                .isEqualTo("REPORT_REFERENCE_MISSING: transaction type");
        assertThat(runRow(runId)).containsEntry("status", "FAILED").containsEntry("failure_code", "JOB_FAILED")
                .containsEntry("job_execution_id", failed.getId()).containsEntry("output_sha256", null);
        assertThat(report(runId)).doesNotExist();
        assertThat(failed.getExecutionContext().containsKey(ReportPublication.WORK)).isFalse();
        // Recovery and a stop leave the row FAILED with their own code; the restart takes each.
        DB.admin().sql("UPDATE batch.report_run SET failure_code = :code WHERE report_run_id = :id")
                .param("code", failureCode).param("id", runId).update();

        DB.admin().sql("INSERT INTO ref.tran_type (type_cd, description) VALUES ('96', 'Added Type')").update();
        DB.admin().sql("INSERT INTO ref.tran_category (type_cd, cat_cd, description) VALUES ('96', '0097', 'Added')")
                .update();
        JobExecution restarted = launch(runId);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getId()).isNotEqualTo(failed.getId());
        assertThat(restarted.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(runRow(runId)).containsEntry("status", "COMPLETED")
                .containsEntry("job_execution_id", restarted.getId()).containsEntry("failure_code", null)
                .containsEntry("output_sha256", sha256(report(runId)));
        assertThat(lines(report(runId))).hasSizeGreaterThan(4);
        assertThat(report(runId).getParent().resolve(
                ReportPublication.TARGET + "." + failed.getId() + ".tmp")).doesNotExist();
        assertThat(output.getAll()).doesNotContain(CARDS.toArray(String[]::new));
    }

    @Test
    void aCompletedRunIsNeverRunAgain() throws Exception {
        long runId = requested();
        JobExecution job = launch(runId);
        assertThat(job.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var row = runRow(runId);
        byte[] bytes = Files.readAllBytes(report(runId));
        assertThatThrownBy(() -> launch(runId)).isInstanceOfSatisfying(InternalApiException.class,
                problem -> assertThat(problem.code()).isEqualTo("JOB_INSTANCE_COMPLETE"));
        assertThat(runRow(runId)).isEqualTo(row);
        assertThat(Files.readAllBytes(report(runId))).isEqualTo(bytes);
    }
}
