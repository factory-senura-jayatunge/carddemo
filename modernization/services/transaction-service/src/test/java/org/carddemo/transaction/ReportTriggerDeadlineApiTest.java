package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.transaction.AirflowStub.Answer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The trigger's deadline through the API, with the production retries and backoff, a timeout
 * longer than the deadline, and an Airflow that never answers the run call: the request ends
 * within the deadline plus 1 s, as a definite trigger failure.
 */
class ReportTriggerDeadlineApiTest extends TransactionTestBase {

    static final AirflowStub AIRFLOW = AirflowStub.start();
    static final Duration DEADLINE = Duration.ofSeconds(2);

    private long lastRunBefore;

    @DynamicPropertySource
    static void silentAirflow(DynamicPropertyRegistry registry) {
        registry.add("carddemo.transaction.report.trigger.url", () -> AIRFLOW.url().toString());
        registry.add("carddemo.transaction.report.trigger.retries", () -> "3");
        registry.add("carddemo.transaction.report.trigger.backoff", () -> "1s");
        registry.add("carddemo.transaction.report.trigger.timeout", () -> "10s");
        registry.add("carddemo.transaction.report.trigger.deadline", () -> "2s");
    }

    @BeforeEach
    void before() {
        AIRFLOW.reset();
        lastRunBefore = admin.sql("SELECT COALESCE(MAX(report_run_id), 0) FROM batch.report_run")
                .query(Long.class).single();
    }

    @AfterEach
    void removeRuns() {
        admin.sql("DELETE FROM batch.report_run WHERE report_run_id > :before AND requested_by = :user")
                .param("before", lastRunBefore).param("user", USER).update();
    }

    @AfterAll
    static void stopAirflow() {
        AIRFLOW.close();
    }

    @Test
    void aSilentAirflowFailsTheRunWithinTheDeadline() throws Exception {
        AIRFLOW.answerRuns(Answer.slow(Duration.ofMinutes(1)), Answer.slow(Duration.ofMinutes(1)),
                Answer.slow(Duration.ofMinutes(1)), Answer.slow(Duration.ofMinutes(1)));
        long started = System.nanoTime();
        expectProblem(mvc.perform(post("/api/reports/transactions")
                        .header("Authorization", bearer(TestTokens.user(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "MONTHLY", "confirm", "Y")))),
                503, "REPORT_TRIGGER_FAILED", "Unable to start the report. Try again later...", "monthly");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).isLessThan(DEADLINE.plusSeconds(1));
        List<Map<String, Object>> rows = admin.sql("""
                        SELECT status, failure_code FROM batch.report_run
                        WHERE report_run_id > :before AND requested_by = :user""")
                .param("before", lastRunBefore).param("user", USER).query().listOfRows();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "TRIGGER_FAILED"));
        assertThat(AIRFLOW.runCalls()).hasSize(1);
    }
}
