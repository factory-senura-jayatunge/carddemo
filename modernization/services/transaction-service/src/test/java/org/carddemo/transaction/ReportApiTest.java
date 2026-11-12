package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.transaction.AirflowStub.Answer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Unit RPTS (CORPT00C) through the API: the request writes one {@code batch.report_run} row
 * through {@code batch.report_run_request} and starts one DAG run on an Airflow stub; the status
 * and the download read the row and the file under the database instance's output root in a
 * scratch CARDDEMO_VAR_DIR. The clock is
 * 2022-07-06. Rows a run's job would write (RUNNING, COMPLETED) are inserted as the admin login,
 * with a fake job execution.
 */
class ReportApiTest extends TransactionTestBase {

    static final AirflowStub AIRFLOW = AirflowStub.start();
    static final Instant TODAY = Instant.parse("2022-07-06T10:00:00Z");
    static final long FAKE_EXECUTION = -7300;
    static final String OTHER_USER = "USER0002";
    static final String RUNS = "/api/reports/transactions";
    /** The file every COMPLETED row of this test stands for: one 133-byte report line. */
    static final byte[] REPORT = ("DailyTransaction Report" + " ".repeat(133 - 23) + "\n")
            .getBytes(StandardCharsets.US_ASCII);

    private long lastRunBefore;

    @DynamicPropertySource
    static void report(DynamicPropertyRegistry registry) {
        registry.add("carddemo.transaction.report.trigger.url", () -> AIRFLOW.url().toString());
        registry.add("carddemo.transaction.report.trigger.timeout", () -> "300ms");
        registry.add("carddemo.transaction.report.trigger.backoff", () -> "10ms");
    }

    @BeforeEach
    void startData() {
        TestClock.shared().set(TODAY);
        AIRFLOW.reset();
        dropFakeExecution();
        admin.sql("INSERT INTO batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)"
                + " VALUES (:id, 0, 'transactionReportJob', 'reportApiTest')").param("id", FAKE_EXECUTION).update();
        admin.sql("INSERT INTO batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME,"
                + " START_TIME, LAST_UPDATED, STATUS) VALUES (:id, 1, :id, GETUTCDATE(), GETUTCDATE(), GETUTCDATE(),"
                + " 'STARTED')").param("id", FAKE_EXECUTION).update();
        lastRunBefore = admin.sql("SELECT COALESCE(MAX(report_run_id), 0) FROM batch.report_run")
                .query(Long.class).single();
    }

    @AfterEach
    void removeRuns() {
        TestClock.shared().reset();
        DB.fixtures().closeBatchWindows();
        admin.sql("DELETE FROM batch.report_run WHERE report_run_id > :before AND requested_by IN (:users)")
                .param("before", lastRunBefore).param("users", List.of(USER, OTHER_USER, ADMIN)).update();
        dropFakeExecution();
    }

    @AfterAll
    static void stopAirflow() {
        AIRFLOW.close();
    }

    private void dropFakeExecution() {
        admin.sql("DELETE FROM batch.execution_lease WHERE job_execution_id = :id").param("id", FAKE_EXECUTION)
                .update();
        admin.sql("DELETE FROM batch.report_run WHERE job_execution_id = :id").param("id", FAKE_EXECUTION).update();
        admin.sql("DELETE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id").param("id", FAKE_EXECUTION)
                .update();
        admin.sql("DELETE FROM batch.BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id").param("id", FAKE_EXECUTION)
                .update();
    }

    static Map<String, Object> form(String type, String confirm) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("type", type);
        form.put("confirm", confirm);
        return form;
    }

    ResultActions request(Map<String, Object> form) throws Exception {
        return request(form, TestTokens.user(USER));
    }

    ResultActions request(Map<String, Object> form, String token) throws Exception {
        return mvc.perform(post(RUNS).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form)));
    }

    ResultActions runStatus(Object runId, String token) throws Exception {
        return mvc.perform(get(RUNS + "/{runId}", runId).header("Authorization", bearer(token)));
    }

    ResultActions download(Object runId, String token) throws Exception {
        return mvc.perform(get(RUNS + "/{runId}/file", runId).header("Authorization", bearer(token)));
    }

    /** The rows this test created, oldest first. */
    List<Map<String, Object>> newRuns() {
        return admin.sql("""
                        SELECT report_run_id, report_type, start_date, end_date, RTRIM(requested_by) AS requested_by,
                            status, failure_code
                        FROM batch.report_run WHERE report_run_id > :before ORDER BY report_run_id""")
                .param("before", lastRunBefore).query().listOfRows();
    }

    /**
     * A run as the job leaves it: REQUESTED without an execution, RUNNING or COMPLETED with the fake
     * one; a COMPLETED row carries the sha256 of {@link #REPORT}, as the job commits it (V424).
     */
    long run(String user, String status) {
        boolean started = !"REQUESTED".equals(status);
        boolean completed = "COMPLETED".equals(status);
        String sql = "INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, status,"
                + " job_execution_id, started_at, finished_at, output_sha256) OUTPUT inserted.report_run_id"
                + " VALUES ('CUSTOM', '2022-07-01', '2022-07-31', :user, :status, "
                + (started ? FAKE_EXECUTION : "NULL") + ", " + (started ? "SYSUTCDATETIME()" : "NULL") + ", "
                + (completed ? "SYSUTCDATETIME()" : "NULL") + ", "
                + (completed ? "0x" + HexFormat.of().formatHex(sha256(REPORT)) : "NULL") + ")";
        return admin.sql(sql).param("user", user).param("status", status).query(Long.class).single();
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The run's file where batch-service writes it: owner-only, under this database's instance root. */
    byte[] reportFile(long runId) throws IOException {
        return reportFile(runId, REPORT);
    }

    byte[] reportFile(long runId, byte[] content) throws IOException {
        Path file = reportFolder(runId).resolve("TRANREPT.txt");
        Files.write(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return content;
    }

    Path reportFolder(long runId) {
        OutputRoot.Instance instance = admin.sql("""
                        SELECT DB_NAME(), CONVERT(CHAR(36), instance_id) FROM ops.database_instance WHERE id = 1""")
                .query((rs, n) -> OutputRoot.Instance.of(rs.getString(1), rs.getString(2))).single();
        Path root = OutputRoot.ensure(OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_PROPERTY, VAR_DIR.toString())::get),
                instance);
        return OutputRoot.ensureDirectory(root, "reports", "tranrept", Long.toString(runId));
    }

    String rowVersion(long runId) {
        return admin.sql("SELECT row_version FROM batch.report_run WHERE report_run_id = :id").param("id", runId)
                .query((rs, n) -> HexFormat.of().formatHex(rs.getBytes(1))).single();
    }

    ResultActions expectCode(ResultActions result, int status, String code, String message) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message));
    }

    /** S1: one REQUESTED row, one DAG run with the run's ID and range, and the success text. */
    @Test
    @SpecItem("SE-JCSD-002")
    void aMonthlyReportIsRequested() throws Exception {
        MvcResult result = request(form("MONTHLY", "Y"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("MONTHLY"))
                .andExpect(jsonPath("$.startDate").value("2022-07-01"))
                .andExpect(jsonPath("$.endDate").value("2022-07-31"))
                .andExpect(jsonPath("$.requestedBy").value(USER))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.stalled").value(false))
                .andExpect(jsonPath("$.downloadable").value(false))
                .andExpect(jsonPath("$.message").value("Monthly report submitted for printing ..."))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long runId = body.path("runId").asLong();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(RUNS + "/" + runId);
        assertThat(body.path("requestedAt").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");

        List<Map<String, Object>> rows = newRuns();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("report_run_id", runId).containsEntry("report_type", "MONTHLY")
                .containsEntry("requested_by", USER).containsEntry("status", "REQUESTED");
        assertThat(rows.get(0).get("start_date").toString()).isEqualTo("2022-07-01");
        assertThat(rows.get(0).get("end_date").toString()).isEqualTo("2022-07-31");

        assertThat(AIRFLOW.runCalls()).hasSize(1);
        JsonNode run = json.readTree(AIRFLOW.runCalls().get(0).body());
        assertThat(run.path("dag_run_id").asText()).isEqualTo("tranrept-" + runId);
        assertThat(run.path("conf").path("runId").asLong()).isEqualTo(runId);
        assertThat(run.path("conf").path("startDate").asText()).isEqualTo("2022-07-01");
        assertThat(run.path("conf").path("endDate").asText()).isEqualTo("2022-07-31");
        assertThat(run.path("conf").path("requestedBy").asText()).isEqualTo(USER);
    }

    /** S2. */
    @Test
    void aYearlyReportCoversTheClocksYear() throws Exception {
        request(form("YEARLY", "Y")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.startDate").value("2022-01-01"))
                .andExpect(jsonPath("$.endDate").value("2022-12-31"))
                .andExpect(jsonPath("$.message").value("Yearly report submitted for printing ..."));
    }

    /** S12: a custom range with one-digit parts and a lower-case y. */
    @Test
    void aCustomReportPadsItsParts() throws Exception {
        Map<String, Object> form = form("CUSTOM", "y");
        form.putAll(Map.of("startMonth", " 7", "startDay", " 1", "startYear", "2022", "endMonth", "7",
                "endDay", "31", "endYear", "2022"));
        request(form).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.startDate").value("2022-07-01"))
                .andExpect(jsonPath("$.endDate").value("2022-07-31"))
                .andExpect(jsonPath("$.message").value("Custom report submitted for printing ..."));
        assertThat(newRuns()).hasSize(1);
    }

    /** S4, S5, S6: a refused request writes no row and starts nothing. */
    @Test
    void aRefusedRequestStartsNothing() throws Exception {
        expectProblem(request(form("MONTHLY", "")), 422, "CONFIRM_REQUIRED",
                "Please confirm to print the Monthly report...", "confirm")
                .andExpect(jsonPath("$.startDate").value("2022-07-01"))
                .andExpect(jsonPath("$.endDate").value("2022-07-31"));
        expectProblem(request(form("MONTHLY", "XYZ")), 400, "CONFIRM_INVALID",
                "\"X\" is not a valid value to confirm...", "confirm");
        expectProblem(request(form(null, "Y")), 400, "REPORT_TYPE_REQUIRED",
                "Select a report type to print report...", "monthly");
        Map<String, Object> custom = form("CUSTOM", "Y");
        custom.putAll(Map.of("startMonth", "07", "startDay", "01", "startYear", "20a2"));
        expectProblem(request(custom), 400, "END_MONTH_REQUIRED", "End Date - Month can NOT be empty...",
                "endMonth");
        assertThat(newRuns()).isEmpty();
        assertThat(AIRFLOW.calls()).isEmpty();
    }

    /** S13: Airflow already has the run. */
    @Test
    void anExistingDagRunCountsAsStarted() throws Exception {
        AIRFLOW.answerRuns(Answer.of(409));
        request(form("MONTHLY", "Y")).andExpect(status().isAccepted());
        assertThat(newRuns()).extracting(r -> r.get("status")).containsExactly("REQUESTED");
    }

    /** S14: two timeouts, then an answer; three calls with the same run ID. */
    @Test
    void timeoutsAreRetried() throws Exception {
        AIRFLOW.answerRuns(Answer.slow(Duration.ofSeconds(1)), Answer.slow(Duration.ofSeconds(1)), Answer.of(200));
        request(form("MONTHLY", "Y")).andExpect(status().isAccepted());
        assertThat(AIRFLOW.runCalls()).hasSize(3);
        assertThat(AIRFLOW.runCalls().stream().map(AirflowStub.Call::body).distinct()).hasSize(1);
    }

    /** S15: a definite refusal fails the row and answers 503. */
    @Test
    void aRefusedTriggerFailsTheRun() throws Exception {
        AIRFLOW.answerRuns(Answer.of(400));
        expectProblem(request(form("MONTHLY", "Y")), 503, "REPORT_TRIGGER_FAILED",
                "Unable to start the report. Try again later...", "monthly");
        assertThat(newRuns()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "TRIGGER_FAILED"));
    }

    /** S16: the job started the run before Airflow's refusal came back. */
    @Test
    void aRunTheJobStartedIsKept() throws Exception {
        AIRFLOW.answerRuns(Answer.of(400)).beforeRunAnswer(call -> {
            long runId;
            try {
                runId = json.readTree(call.body()).path("conf").path("runId").asLong();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            admin.sql("""
                            UPDATE batch.report_run
                            SET status = 'RUNNING', job_execution_id = :exec, started_at = SYSUTCDATETIME()
                            WHERE report_run_id = :id""")
                    .param("exec", FAKE_EXECUTION).param("id", runId).update();
        });
        request(form("MONTHLY", "Y")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.message").value("Monthly report submitted for printing ..."));
        assertThat(newRuns()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "RUNNING").containsEntry("failure_code", null));
    }

    /** S17: an old REQUESTED row reads as NOT_STARTED, and the read writes nothing. */
    @Test
    void anOldRequestReadsAsNotStarted() throws Exception {
        long old = run(USER, "REQUESTED");
        long recent = run(USER, "REQUESTED");
        admin.sql("UPDATE batch.report_run SET requested_at = DATEADD(MINUTE, -16, SYSUTCDATETIME())"
                + " WHERE report_run_id = :id").param("id", old).update();
        admin.sql("UPDATE batch.report_run SET requested_at = DATEADD(MINUTE, -14, SYSUTCDATETIME())"
                + " WHERE report_run_id = :id").param("id", recent).update();
        String version = rowVersion(old);

        runStatus(old, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.message").doesNotExist());
        runStatus(recent, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        assertThat(rowVersion(old)).isEqualTo(version);
        assertThat(admin.sql("SELECT status FROM batch.report_run WHERE report_run_id = :id").param("id", old)
                .query(String.class).single()).isEqualTo("REQUESTED");
    }

    /** S18, S25: another user's run is not found; an ADMIN reads and downloads any run, unaudited. */
    @Test
    void runsAreTheRequestersOrAnAdmins() throws Exception {
        long others = run(OTHER_USER, "COMPLETED");
        byte[] file = reportFile(others);
        int audits = auditRows();

        expectCode(runStatus(others, TestTokens.user(USER)), 404, "REPORT_RUN_NOT_FOUND",
                "Report run not found...");
        expectCode(download(others, TestTokens.user(USER)), 404, "REPORT_RUN_NOT_FOUND", "Report run not found...");
        expectCode(runStatus(Long.MAX_VALUE, TestTokens.user(USER)), 404, "REPORT_RUN_NOT_FOUND",
                "Report run not found...");
        runStatus(others, TestTokens.admin(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedBy").value(OTHER_USER))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadable").value(true));
        assertThat(download(others, TestTokens.admin(ADMIN)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(file);
        runStatus(others, TestTokens.user(OTHER_USER)).andExpect(status().isOk());
        assertThat(auditRows()).isEqualTo(audits);
    }

    /** S19, S25: the file of a completed run only, with its bytes and headers; no audit row. */
    @Test
    void onlyACompletedRunIsDownloaded() throws Exception {
        long running = run(USER, "RUNNING");
        long completed = run(USER, "COMPLETED");
        byte[] file = reportFile(completed);
        reportFile(running);
        int audits = auditRows();

        expectCode(download(running, TestTokens.user(USER)), 409, "REPORT_NOT_READY",
                "The report is not ready yet...");
        runStatus(running, TestTokens.user(USER)).andExpect(jsonPath("$.downloadable").value(false))
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.finishedAt").doesNotExist());
        MvcResult result = download(completed, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain;charset=US-ASCII")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"TRANREPT-" + completed + ".txt\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().longValue("Content-Length", file.length))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(file);
        assertThat(auditRows()).isEqualTo(audits);
        assertThat(stagingNames()).isEmpty();
    }

    /**
     * 16: a file whose bytes differ from the sha256 committed with COMPLETED is refused as missing
     * and never sent, and its snapshot leaves no name behind. The file itself is left as it is.
     */
    @Test
    void aFileThatDiffersFromItsHashIsNotSent() throws Exception {
        long completed = run(USER, "COMPLETED");
        byte[] changed = REPORT.clone();
        changed[0] = 'd';
        reportFile(completed, changed);
        MvcResult result = expectCode(download(completed, TestTokens.user(USER)), 500, "REPORT_FILE_MISSING",
                "The report file is not available...").andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).isNull();
        assertThat(Files.readAllBytes(reportFolder(completed).resolve("TRANREPT.txt"))).isEqualTo(changed);
        assertThat(stagingNames()).isEmpty();
        reportFile(completed);
        download(completed, TestTokens.user(USER)).andExpect(status().isOk());
    }

    /** What is left in the default staging folder; it may not exist before the first download. */
    List<String> stagingNames() throws IOException {
        Path folder = VAR_DIR.resolve("private").resolve("transaction-service");
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }

    /** S20. */
    @Test
    void aMissingFileIsAServerError() throws Exception {
        long completed = run(USER, "COMPLETED");
        expectCode(download(completed, TestTokens.user(USER)), 500, "REPORT_FILE_MISSING",
                "The report file is not available...");
        reportFolder(completed);
        expectCode(download(completed, TestTokens.user(USER)), 500, "REPORT_FILE_MISSING",
                "The report file is not available...");
    }

    /** S20: a file that others may read is refused as missing, and never sent. */
    @Test
    void aFileOpenToOthersIsNotSent() throws Exception {
        long completed = run(USER, "COMPLETED");
        reportFile(completed);
        Files.setPosixFilePermissions(reportFolder(completed).resolve("TRANREPT.txt"),
                PosixFilePermissions.fromString("rw-r--r--"));
        expectCode(download(completed, TestTokens.user(USER)), 500, "REPORT_FILE_MISSING",
                "The report file is not available...");
    }

    /** ERR-RPTS-003: the download reads the instance marker with the row; a refused read is the generic 500. */
    @Test
    void aRefusedMarkerReadIsTheGenericProblem() throws Exception {
        long completed = run(USER, "COMPLETED");
        reportFile(completed);
        deny("SELECT ON OBJECT::ops.database_instance", () ->
                expectCode(download(completed, TestTokens.user(USER)), 500, "INTERNAL_ERROR",
                        MessageCatalog.INTERNAL_ERROR.text()));
        download(completed, TestTokens.user(USER)).andExpect(status().isOk());
    }

    /** 1 to 19 digits, leading zeros allowed, 1 to the BIGINT maximum; anything else is 400, not 404. */
    @Test
    void aRunIdIsAPositiveNumber() throws Exception {
        long own = run(USER, "REQUESTED");
        String padded = "0".repeat(19 - Long.toString(own).length()) + own;
        runStatus(padded, TestTokens.user(USER)).andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(own));
        runStatus("00" + own, TestTokens.user(USER)).andExpect(status().isOk());
        for (String bad : List.of("abc", "0", "000", "-1", "+1", "1.5", "9223372036854775808", "99999999999999999999",
                "0" + padded)) {
            expectCode(runStatus(bad, TestTokens.user(USER)), 400, "INVALID_RUN_ID",
                    MessageCatalog.INVALID_RUN_ID.text());
            expectCode(download(bad, TestTokens.user(USER)), 400, "INVALID_RUN_ID",
                    MessageCatalog.INVALID_RUN_ID.text());
        }
    }

    /** S21: an ADMIN may not request; no token is 401 everywhere. */
    @Test
    void rolesAndTokens() throws Exception {
        long own = run(USER, "REQUESTED");
        request(form("MONTHLY", "Y"), TestTokens.admin(ADMIN)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(form("MONTHLY", "Y"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get(RUNS + "/{runId}", own)).andExpect(status().isUnauthorized());
        mvc.perform(get(RUNS + "/{runId}/file", own)).andExpect(status().isUnauthorized());
        assertThat(newRuns()).hasSize(1);
        assertThat(AIRFLOW.runCalls()).isEmpty();
    }

    /** S22: the request writes no table a batch window blocks. */
    @Test
    void aReportIsAcceptedDuringABatchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        request(form("MONTHLY", "Y")).andExpect(status().isAccepted());
        assertThat(AIRFLOW.runCalls()).hasSize(1);
    }

    /** S23: a RUNNING row whose execution's lease is past its owner's limit is stalled. */
    @Test
    void aRunWithALostLeaseIsStalled() throws Exception {
        long running = run(USER, "RUNNING");
        runStatus(running, TestTokens.user(USER)).andExpect(jsonPath("$.stalled").value(false));
        admin.sql("""
                        INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid,
                            renewed_at, recoverable_after_ms)
                        VALUES (:id, NEWID(), 'report-api-test', 1, DATEADD(MINUTE, -10, SYSUTCDATETIME()), 60000)""")
                .param("id", FAKE_EXECUTION).update();
        runStatus(running, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stalled").value(true));
    }

    /** ERR-RPTS-003: a failed write is the generic 500 with a correlation ID; nothing is started. */
    @Test
    void aFailedWriteIsTheGenericProblem() throws Exception {
        deny("EXECUTE ON OBJECT::batch.report_run_request", () -> {
            MvcResult failed = expectCode(request(form("MONTHLY", "Y")), 500, "INTERNAL_ERROR",
                    MessageCatalog.INTERNAL_ERROR.text()).andReturn();
            assertThat(failed.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
            assertThat(failed.getResponse().getContentAsString()).doesNotContainIgnoringCase("report_run")
                    .doesNotContainIgnoringCase("transaction_svc").doesNotContainIgnoringCase("EXECUTE");
        });
        assertThat(newRuns()).isEmpty();
        assertThat(AIRFLOW.runCalls()).isEmpty();
    }

    /**
     * ERR-RPTS-003 after the trigger: Airflow refuses the run and the failure cannot be recorded.
     * The same generic 500; the row stays REQUESTED and reads as NOT_STARTED once it is old.
     */
    @Test
    void aTriggerFailureThatCannotBeRecordedIsTheGenericProblem() throws Exception {
        AIRFLOW.answerRuns(Answer.of(400));
        deny("EXECUTE ON OBJECT::batch.report_run_trigger_failed", () -> {
            MvcResult failed = expectCode(request(form("MONTHLY", "Y")), 500, "INTERNAL_ERROR",
                    MessageCatalog.INTERNAL_ERROR.text()).andReturn();
            assertThat(failed.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
            assertThat(failed.getResponse().getContentAsString()).doesNotContainIgnoringCase("report_run")
                    .doesNotContainIgnoringCase("transaction_svc").doesNotContainIgnoringCase("EXECUTE");
        });
        assertThat(AIRFLOW.runCalls()).hasSize(1);
        Map<String, Object> row = newRuns().getFirst();
        assertThat(newRuns()).hasSize(1);
        assertThat(row).containsEntry("status", "REQUESTED").containsEntry("failure_code", null);
        long runId = (Long) row.get("report_run_id");
        runStatus(runId, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        admin.sql("UPDATE batch.report_run SET requested_at = DATEADD(MINUTE, -16, SYSUTCDATETIME())"
                + " WHERE report_run_id = :id").param("id", runId).update();
        runStatus(runId, TestTokens.user(USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
    }

    /**
     * ERR-RPTS-003 on a read. The view is denied, not the table: a table-level DENY does not
     * override the column-level SELECT grant on batch.report_run.
     */
    @Test
    void aFailedStatusReadIsTheGenericProblem() throws Exception {
        long own = run(USER, "REQUESTED");
        deny("SELECT ON OBJECT::batch.v_report_run_stalled", () -> {
            MvcResult failed = expectCode(runStatus(own, TestTokens.user(USER)), 500, "INTERNAL_ERROR",
                    MessageCatalog.INTERNAL_ERROR.text()).andReturn();
            assertThat(failed.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
            assertThat(failed.getResponse().getContentAsString()).doesNotContainIgnoringCase("report_run")
                    .doesNotContainIgnoringCase("transaction_svc").doesNotContainIgnoringCase("SELECT");
        });
    }
}
