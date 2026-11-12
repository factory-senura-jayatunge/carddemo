package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.carddemo.transaction.AirflowStub.Answer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The DAG trigger against a stub of the Airflow REST API (contract step 6 and 7, RPTS S13 to S15). */
@ExtendWith(OutputCaptureExtension.class)
class AirflowReportTriggerTest {

    static final AirflowStub AIRFLOW = AirflowStub.start();
    static final String PASSWORD = "stub-password-not-logged";
    static final LocalDate START = LocalDate.of(2022, 7, 1);
    static final LocalDate END = LocalDate.of(2022, 7, 31);

    private final List<Duration> waits = new CopyOnWriteArrayList<>();
    /** Moves only when the trigger waits, so the backoff counts against the deadline without real sleeps. */
    private final AtomicLong now = new AtomicLong();
    private AirflowReportTrigger trigger;

    @BeforeEach
    void freshTrigger() {
        AIRFLOW.reset();
        waits.clear();
        trigger = withFakeClock(settings(AIRFLOW.url(), 3, Duration.ofSeconds(1), Duration.ofMillis(300),
                Duration.ofSeconds(30)));
    }

    static ReportProperties.Trigger settings(URI url, int retries, Duration backoff, Duration timeout,
                                             Duration deadline) {
        return new ReportProperties.Trigger(url, "transaction-service", PASSWORD, retries, backoff, timeout, deadline);
    }

    AirflowReportTrigger withFakeClock(ReportProperties.Trigger settings) {
        return new AirflowReportTrigger(settings, wait -> {
            waits.add(wait);
            now.addAndGet(wait.toNanos());
        }, now::get);
    }

    @AfterAll
    static void stop() {
        AIRFLOW.close();
    }

    @Test
    void signsOnThenCreatesTheRunWithItsConf() throws Exception {
        assertThat(trigger.trigger(17, START, END, "USER0001")).isTrue();
        List<AirflowStub.Call> calls = AIRFLOW.calls();
        assertThat(calls).extracting(AirflowStub.Call::path).containsExactly("/auth/token", AirflowStub.RUNS_PATH);
        JsonNode signOn = new ObjectMapper().readTree(calls.get(0).body());
        assertThat(signOn.path("username").asText()).isEqualTo("transaction-service");
        assertThat(signOn.path("password").asText()).isEqualTo(PASSWORD);
        AirflowStub.Call run = calls.get(1);
        assertThat(run.method()).isEqualTo("POST");
        assertThat(run.authorization()).isEqualTo("Bearer " + AirflowStub.TOKEN);
        JsonNode body = new ObjectMapper().readTree(run.body());
        assertThat(body.path("dag_run_id").asText()).isEqualTo("tranrept-17");
        assertThat(body.has("logical_date")).isTrue();
        assertThat(body.path("logical_date").isNull()).isTrue();
        assertThat(body.path("conf").path("runId").asLong()).isEqualTo(17);
        assertThat(body.path("conf").path("startDate").asText()).isEqualTo("2022-07-01");
        assertThat(body.path("conf").path("endDate").asText()).isEqualTo("2022-07-31");
        assertThat(body.path("conf").path("requestedBy").asText()).isEqualTo("USER0001");
        assertThat(waits).isEmpty();

        assertThat(trigger.trigger(18, START, END, "USER0001")).isTrue();
        assertThat(AIRFLOW.calls()).as("the token is kept").hasSize(3);
    }

    @Test
    void anExistingRunCountsAsStarted() {
        AIRFLOW.answerRuns(Answer.of(409));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isTrue();
        assertThat(AIRFLOW.runCalls()).hasSize(1);
    }

    @Test
    void timeoutsAreRetriedWithBackoff() {
        AIRFLOW.answerRuns(Answer.slow(Duration.ofSeconds(2)), Answer.slow(Duration.ofSeconds(2)), Answer.of(200));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isTrue();
        assertThat(AIRFLOW.runCalls()).hasSize(3);
        assertThat(AIRFLOW.runCalls()).as("every attempt creates the same run").extracting(AirflowStub.Call::body)
                .containsOnly(AirflowReportTrigger.runBody(17, START, END, "USER0001"));
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void aRefusalIsDefinite(CapturedOutput output) {
        AIRFLOW.answerRuns(Answer.of(400));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.runCalls()).hasSize(1);
        assertThat(waits).isEmpty();
        assertThat(output.getAll()).contains("HTTP 400").doesNotContain(PASSWORD).doesNotContain(AirflowStub.TOKEN);
    }

    @Test
    void retriesUsedUpAreDefinite(CapturedOutput output) {
        AIRFLOW.answerRuns(Answer.of(503), Answer.of(502), Answer.of(500), Answer.of(503));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.runCalls()).hasSize(4);
        assertThat(AIRFLOW.runCalls()).as("every attempt creates the same run").extracting(AirflowStub.Call::body)
                .containsOnly(AirflowReportTrigger.runBody(17, START, END, "USER0001"));
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(AirflowStub.TOKEN);
    }

    @Test
    void anUnreachableAirflowIsRetriedThenDefinite() {
        int port = AIRFLOW.url().getPort();
        AirflowStub other = AirflowStub.start();
        URI closed = other.url();
        other.close();
        AirflowReportTrigger unreachable = withFakeClock(settings(closed, 3, Duration.ofSeconds(1),
                Duration.ofMillis(300), Duration.ofSeconds(30)));
        assertThat(closed.getPort()).isNotEqualTo(port);
        assertThat(unreachable.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void anExpiredTokenIsRenewedOnce() {
        assertThat(trigger.trigger(17, START, END, "USER0001")).isTrue();
        AIRFLOW.reset().answerRuns(Answer.of(401), Answer.of(200));
        assertThat(trigger.trigger(18, START, END, "USER0001")).isTrue();
        assertThat(AIRFLOW.calls()).extracting(AirflowStub.Call::path)
                .containsExactly(AirflowStub.RUNS_PATH, "/auth/token", AirflowStub.RUNS_PATH);
        assertThat(AIRFLOW.runCalls()).extracting(AirflowStub.Call::body)
                .containsOnly(AirflowReportTrigger.runBody(18, START, END, "USER0001"));
    }

    @Test
    void aRefusedSignOnIsDefinite() {
        AIRFLOW.signOnAnswers(401);
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.runCalls()).isEmpty();
        assertThat(waits).isEmpty();
    }

    @Test
    void theSettingsNeverPrintThePassword() {
        assertThat(settings(AIRFLOW.url(), 3, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30))
                .toString()).doesNotContain(PASSWORD);
    }

    /** RPTS deadline: a stub that never answers ends the trigger at the deadline, not after the retries. */
    @Test
    void aSilentAirflowEndsTheTriggerAtItsDeadline() {
        AIRFLOW.answerRuns(Answer.slow(Duration.ofMinutes(1)), Answer.slow(Duration.ofMinutes(1)),
                Answer.slow(Duration.ofMinutes(1)), Answer.slow(Duration.ofMinutes(1)));
        AirflowReportTrigger real = new AirflowReportTrigger(settings(AIRFLOW.url(), 3, Duration.ofSeconds(1),
                Duration.ofSeconds(10), Duration.ofSeconds(2)));
        long started = System.nanoTime();
        assertThat(real.trigger(17, START, END, "USER0001")).isFalse();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1900)).isLessThan(Duration.ofSeconds(3));
        assertThat(AIRFLOW.runCalls()).hasSize(1);
    }

    @Test
    void noAttemptStartsAfterTheDeadline() {
        AIRFLOW.answerRuns(Answer.of(503), Answer.of(503), Answer.of(503), Answer.of(503));
        AirflowReportTrigger bounded = withFakeClock(settings(AIRFLOW.url(), 3, Duration.ofSeconds(1),
                Duration.ofMillis(300), Duration.ofMillis(2500)));
        assertThat(bounded.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.runCalls()).as("the third attempt would start 3 s in").hasSize(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aRunFoundAfterUnknownOutcomesCountsAsStarted() {
        AIRFLOW.answerRuns(Answer.of(503), Answer.slow(Duration.ofSeconds(2)), Answer.of(409));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isTrue();
        assertThat(AIRFLOW.runCalls()).hasSize(3).extracting(AirflowStub.Call::body)
                .containsOnly(AirflowReportTrigger.runBody(17, START, END, "USER0001"));
    }

    @Test
    void aSignOnWithoutATokenIsRetried() {
        AIRFLOW.signOnAnswers(200);
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.calls()).extracting(AirflowStub.Call::path).containsOnly("/auth/token").hasSize(4);
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 403, 404, 422, 429})
    void otherRunAnswersAreDefinite(int status) {
        AIRFLOW.answerRuns(Answer.of(status));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.runCalls()).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 403})
    void otherSignOnAnswersAreDefinite(int status) {
        AIRFLOW.signOnAnswers(status);
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.calls()).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void aSecond401IsDefinite() {
        AIRFLOW.answerRuns(Answer.of(401), Answer.of(401));
        assertThat(trigger.trigger(17, START, END, "USER0001")).isFalse();
        assertThat(AIRFLOW.calls()).extracting(AirflowStub.Call::path)
                .containsExactly("/auth/token", AirflowStub.RUNS_PATH, "/auth/token", AirflowStub.RUNS_PATH);
        assertThat(waits).isEmpty();
    }
}
